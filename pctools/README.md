# pctools — PC 側の補助ツール

アプリ単体で登録〜検査は完結します。ここにあるのは **手法の見極めとしきい値調整を PC で行うための補助** です。

```
pip install -r requirements.txt
```

| スクリプト | 役割 |
|---|---|
| `analyze_log.py verify_log.csv [feedback.csv]` | アプリの「検査ログを共有」で出した CSV から、手法ごとの本人/他製品スコア分布・分離余裕・推奨しきい値を表示 |
| `simulate.py [--trials N] [--out dir]` | アプリと同じアルゴリズムの Python 版で合成画像(黒背景+押印ウレタン)を生成し、距離・回転・傾き・照明を振ったときの各手法の分離度を確認 |

アプリの「エクスポート(zip)」で取り出したテンプレートは `products/<id>/product.json` と `parts/<n>/views/*.png` の素直な構造なので、
必要になれば PC で画像を追加して zip に戻し「インポート」できます(同じ id は上書き)。
