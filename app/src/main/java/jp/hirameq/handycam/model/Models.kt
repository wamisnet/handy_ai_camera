package jp.hirameq.handycam.model

import java.util.UUID

/** 製品の種類。セグメンテーション/照合の既定パラメータを切り替えるためのヒント。 */
enum class ProductKind {
    /** 黒背景上のウレタンフォーム。2 パーツの組み合わせを同時に検査。押印文字あり。 */
    FOAM_PAIR,
    /** グレーの箱に入った金属部品。穴パターンが特徴。 */
    METAL_IN_BOX,
    /** 単一物体の汎用。 */
    SINGLE,
}

/** 背景の性質。セグメンテーション手法の既定選択に使う。 */
enum class BackgroundKind { BLACK, GRAY_BOX, AUTO }

/**
 * 正規化画像(canonical crop)上の注目領域。座標は 0..1 の正規化値。
 * ユーザが「ここを重点的に見ろ」と指示するためのもの。
 */
data class Roi(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val weight: Float = 2.0f,
    val label: String = "",
)

/** 登録済みの 1 ビュー(1 枚の正規化画像)。 */
data class TemplateView(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val file: String,           // parts/<i>/views/<id>.png  (canonical RGB)
    val maskFile: String,       // parts/<i>/views/<id>_mask.png
    val flash: Boolean,
    val capturedAt: Long = System.currentTimeMillis(),
    val note: String = "",
)

/** 製品を構成する 1 パーツ(ウレタンの片側 / 金属部品本体など)。 */
data class PartTemplate(
    val name: String,
    val rois: MutableList<Roi> = mutableListOf(),
    val views: MutableList<TemplateView> = mutableListOf(),
    /** 表裏反転(鏡像)を同一と見なすか。 */
    val allowMirror: Boolean = false,
)

/** 製品定義。 */
data class Product(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    var name: String,
    var kind: ProductKind = ProductKind.SINGLE,
    var background: BackgroundKind = BackgroundKind.AUTO,
    val parts: MutableList<PartTemplate> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    /** 製品ごとの上書き設定(空なら全体設定を使う)。 */
    var settingsOverride: MutableMap<String, String> = mutableMapOf(),
) {
    val partCount: Int get() = parts.size
    val viewCount: Int get() = parts.sumOf { it.views.size }
}

/** 撮影シーケンスの 1 ステップ。 */
enum class FlashStep { OFF, ON }

/** 照合手法の識別子。UI/設定/ログで共通利用。 */
enum class MethodId(val label: String, val shortLabel: String) {
    SHAPE("形状(輪郭/Huモーメント)", "Shape"),
    FEATURE("局所特徴(ORB/AKAZE+RANSAC)", "Feature"),
    HOG("勾配ヒストグラム(HOG)", "HOG"),
    NCC("整列後の相関(勾配NCC)", "NCC"),
    EMBOSS("押印エッジ(Chamfer)", "Emboss"),
    HOLES("穴パターン", "Holes"),
    EMBEDDING("CNN埋め込み(TFLite)", "Embed"),
}

enum class FeatureDetectorKind { ORB, AKAZE, SIFT }
enum class SegmenterKind { AUTO, DARK_BG_OTSU, FLASH_DIFF, GRAY_BOX_COLOR }
enum class FusionMode {
    /** 有効な全手法が個別しきい値を満たす必要がある(最も安全)。 */
    ALL_PASS,
    /** 重み付き平均が総合しきい値を超えればよい。 */
    WEIGHTED,
    /** 重み付き平均 かつ 必須(gate)手法は個別に通過。 */
    WEIGHTED_WITH_GATES,
}

/** 1 手法分の設定。 */
data class MethodConfig(
    var enabled: Boolean = true,
    var weight: Float = 1.0f,
    /** 本人スコアがこれ未満なら NG。 */
    var threshold: Float = 0.5f,
    /** 本人スコア − 最良他者スコア がこれ未満なら NG(他製品との識別余裕)。 */
    var minMargin: Float = 0.05f,
    /** WEIGHTED_WITH_GATES のとき必須扱いにするか。 */
    var gate: Boolean = false,
)

/** アプリ全体の設定。SharedPreferences に JSON で保持。 */
data class AppSettings(
    // 撮影
    var flashSequence: MutableList<FlashStep> = mutableListOf(FlashStep.OFF, FlashStep.ON),
    var framesPerStep: Int = 2,
    var settleMillis: Long = 350,
    var analysisLongEdge: Int = 1024,
    // 正規化
    var canonicalLongEdge: Int = 384,
    // セグメンテーション
    var segmenter: SegmenterKind = SegmenterKind.AUTO,
    var minObjectAreaRatio: Float = 0.01f,   // 画面面積比。これ未満のブロブはノイズ
    var morphKernel: Int = 5,
    var flashDiffThreshold: Int = 25,
    var grayBoxColorDistance: Int = 35,
    /** グレー箱: 一番上の箱の内側矩形を検出して正面視に補正し、その内側だけを解析する。 */
    var boxRectify: Boolean = true,
    /** プレビューのガイド枠(中央の何割か)。解析もこの枠内に限定。1.0 で無効。 */
    var guideFrameRatio: Float = 0.9f,
    /** 期待数より多く物体が検出されたフレームを不採用にする(面積上位を黙って採用しない)。 */
    var rejectExtraObjects: Boolean = true,
    // 照合
    var featureDetector: FeatureDetectorKind = FeatureDetectorKind.ORB,
    var fusion: FusionMode = FusionMode.WEIGHTED_WITH_GATES,
    var overallThreshold: Float = 0.6f,
    var overallMinMargin: Float = 0.08f,
    /** バースト中の各フレームスコアの集約: MEDIAN / MAX / MEAN */
    var frameAggregation: String = "MEDIAN",
    var methods: MutableMap<MethodId, MethodConfig> = defaultMethods(),
    // ログ
    var saveDebugImages: Boolean = true,
) {
    companion object {
        fun defaultMethods(): MutableMap<MethodId, MethodConfig> = mutableMapOf(
            MethodId.SHAPE to MethodConfig(enabled = true, weight = 1.0f, threshold = 0.6f, minMargin = 0.0f, gate = true),
            MethodId.FEATURE to MethodConfig(enabled = true, weight = 2.0f, threshold = 0.25f, minMargin = 0.05f, gate = false),
            MethodId.HOG to MethodConfig(enabled = true, weight = 1.5f, threshold = 0.55f, minMargin = 0.05f, gate = false),
            MethodId.NCC to MethodConfig(enabled = true, weight = 1.5f, threshold = 0.35f, minMargin = 0.05f, gate = false),
            MethodId.EMBOSS to MethodConfig(enabled = true, weight = 1.5f, threshold = 0.45f, minMargin = 0.05f, gate = false),
            MethodId.HOLES to MethodConfig(enabled = true, weight = 2.0f, threshold = 0.7f, minMargin = 0.1f, gate = false),
            MethodId.EMBEDDING to MethodConfig(enabled = false, weight = 1.0f, threshold = 0.8f, minMargin = 0.03f, gate = false),
        )
    }
}
