# アーキテクチャ

## 全体パイプライン

```
 CameraX ImageAnalysis (YUV_420_888, ~1280x960)
        │  ImageConvert.imageProxyToBgr  (回転補正・長辺 1024 に縮小)
        ▼
 Frame(bgr, flash)  ×  フラッシュシーケンス [OFF, ON] × framesPerStep
        │
        ▼
 Segmenter ─── 黒背景: Otsu ∧ フラッシュ差分 / グレー箱: Lab 色距離 ─── → 前景マスク・物体一覧
        │           (モルフォロジー → 外側輪郭 → 面積フィルタ → 期待数と比較)
        ▼
 Canonicalizer ── minAreaRect で長軸を水平に回転、長辺一定に拡縮、中央配置、マスク外を黒 ──→ CanonicalImage
        │           (180°/鏡像の曖昧さは variants() で列挙し、手法側で max を取る)
        ▼
 Matcher 群 (match/)      各手法: compare(query, template, rois) → MethodScore(0..1)
        │   Shape / Feature / HOG / NCC / Emboss / Holes / Embedding
        ▼
 Scoring.scorePart  ── max over テンプレートビュー × 向きバリアント
 VerificationEngine ── フレーム間集約(中央値) → パーツ↔物体の割り当て(全順列) → 他製品(impostor)との比較
        ▼
 Decision.decide   ── 手法別 (しきい値 ∧ 余裕) → 融合 (ALL_PASS / WEIGHTED / WEIGHTED_WITH_GATES)
        ▼
 VerificationResult → ResultActivity 表示 / VerifyLog CSV 追記
```

## なぜこの構成か(撮影条件が毎回変わることへの吸収)

| 変動 | 吸収する場所 |
|---|---|
| 距離(スケール) | Canonicalizer が長辺を一定に正規化。Feature は RANSAC ホモグラフィで残差を吸収し、推定スケールが 0.5〜2 を外れたら不採用 |
| 面内回転 | minAreaRect の角度で水平化。180° の曖昧さは variants() で両方評価 |
| 傾き(パース) | Feature(ホモグラフィ)、NCC の ECC アフィン整列、Emboss の平行移動探索、HOG のセル単位の寛容さ。強いパースは「複数角度で登録」で対処 |
| 照明(フラッシュ有無、影、露出) | 輝度を直接比較しない: CLAHE → 勾配 / 高域強調 / エッジ / 勾配方向ヒストグラム。テンプレートもフラッシュあり・なし両方を保存 |
| 背景の映り込み | フラッシュ差分(近い物体だけ明るくなる) ∧ Otsu、面積フィルタ。グレー箱は画像周辺から背景色を推定 |
| 手ブレ・ノイズ | 複数フレームの中央値集約、ぼかし後の勾配 |
| 偶然の一致 | **他製品との識別余裕(margin)** を手法別・総合の両方で要求。「本人が高い」だけでは通さない |

## 判定ロジック(`verify/Decision.kt`)

- 手法ごと: `pass = self ≥ threshold && (self − bestImpostor) ≥ minMargin`
- 融合:
  - `ALL_PASS`: 有効な全手法が pass(最も保守的)
  - `WEIGHTED`: 重み付き平均 ≥ overallThreshold かつ 平均の余裕 ≥ overallMinMargin
  - `WEIGHTED_WITH_GATES`(既定): 上記 + gate 指定手法は個別に pass 必須(既定では Shape が gate)
- 他製品が 1 つも登録されていない場合は margin 条件をスキップし、その旨を理由に表示

## パーツ(2 点セット)の扱い

- 製品は `parts[]` を持ち、ウレタンは 2 パーツ。検査時はセグメント数 = パーツ数 のフレームだけ採用
- パーツ↔セグメントの対応付けは総合スコア行列の全順列最大(`Decision.assign`)
- 各パーツ個別に合否 → 全パーツ OK で製品 OK。「A と B を入れ替えて置いた」場合も対応付けで吸収される
- 登録時: 初回は左→右の並び順、2 回目以降は既存ビューとの HOG+Shape で対応付け。向きは初回ビューに揃える(`alignOrientationTo`)

## コストの抑え方

他製品(impostor)は登録数に比例して増える。安価な手法(Shape, HOG, Holes: cost=1)は全候補に回し、
高価な手法(Feature, NCC, Emboss: cost=2)は安価スコア上位 4 パーツだけに回す(`VerificationEngine.verify`)。
記述子は `CanonicalImage.cached()` にビュー単位でキャッシュされ、ライブラリ読込後は再計算されない。

## データレイアウト

```
files/products/<productId>/product.json
files/products/<productId>/parts/<n>/views/<viewId>.png       正規化 BGR (384x384)
files/products/<productId>/parts/<n>/views/<viewId>_mask.png  正規化マスク
files/logs/verify_log.csv     検査ログ(手法別 self/impostor/pass)
files/logs/feedback.csv       結果画面での「正しい/誤り」
files/models/embedding.tflite (任意) CNN 埋め込みモデル
cache/debug/*.jpg             直近検査のデバッグ画像
```

`product.json` は Gson でそのまま `model/Models.kt` の `Product` を書き出したもの。

## 拡張ポイント

- **手法追加**: `match/Matcher` を実装し `MatcherRegistry.all()` に追加、`MethodId` に enum を追加(設定・ログ・UI は enum 走査なので自動追従)
- **セグメンテーション追加**: `SegmenterKind` に追加し `Segmenter.segment` で分岐
- **CNN 埋め込み**: `files/models/embedding.tflite` を置き設定で有効化(入力 1×224×224×3 float [-1,1]、出力 1×D)。
  MobileNetV3-Small(ImageNet) の分類層を外して TFLite 変換したものを想定
- **PC 事前学習**: エクスポート zip を PC で展開し画像を追加 → 再 zip → インポート。`pctools/README.md`
- **箱の矩形によるパース補正**(未実装): グレー箱の四隅を検出して warpPerspective すれば金属部品の傾き耐性が上がる。`Segmenter.grayBoxColor` の前段に追加する想定
