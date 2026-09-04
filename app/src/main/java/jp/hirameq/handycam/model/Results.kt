package jp.hirameq.handycam.model

/** 1 手法 × 1 フレーム × 1 パーツ候補 の照合結果。 */
data class MethodScore(
    val method: MethodId,
    /** 0..1。1 が完全一致。手法によって「距離」を 1/(1+d) 等で正規化。 */
    val score: Float,
    /** 手法固有の補足(インライア数, 推定スケール等)。 */
    val detail: String = "",
    /** この手法がそのフレームで評価不能(特徴なし等)だった場合 true。 */
    val unavailable: Boolean = false,
)

/** 1 パーツについて、期待製品 vs 他製品 の比較を含む判定。 */
data class PartVerdict(
    val partIndex: Int,
    val partName: String,
    val segmentIndex: Int,
    /** 手法ごとの本人スコア(フレーム集約後)。 */
    val selfScores: Map<MethodId, Float>,
    /** 手法ごとの最良他者スコア(他製品の全パーツ中の最大)。 */
    val bestImpostorScores: Map<MethodId, Float>,
    val bestImpostorName: String,
    val methodPass: Map<MethodId, Boolean>,
    val fused: Float,
    val fusedImpostor: Float,
    val pass: Boolean,
    val reasons: List<String>,
)

data class VerificationResult(
    val productId: String,
    val productName: String,
    val expectedParts: Int,
    val detectedSegments: Int,
    val partVerdicts: List<PartVerdict>,
    val pass: Boolean,
    val summary: String,
    val elapsedMillis: Long,
    /** デバッグ画像ファイルパス(正規化画像, マスク, 特徴点マッチ等)。 */
    val debugImages: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)
