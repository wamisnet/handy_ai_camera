package jp.hirameq.handycam.verify

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.imaging.Canonicalizer
import jp.hirameq.handycam.imaging.Frame
import jp.hirameq.handycam.imaging.Segmenter
import jp.hirameq.handycam.match.MatcherRegistry
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.PartVerdict
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.model.VerificationResult
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * 検査本体。
 *
 * 1. 各フレームをセグメンテーション(フラッシュ有無ペアがあれば差分を併用)し、期待パーツ数と一致するフレームだけ採用
 * 2. 物体を canonical 化(向きバリアント付き)
 * 3. 期待製品の各パーツ & 他製品の全パーツ に対し、手法ごとのスコアを計算(フレーム間で集約)
 * 4. パーツ↔セグメントの割り当てを総合スコア最大で決定
 * 5. 手法別しきい値・他製品との余裕・融合ルールで合否
 */
class VerificationEngine(
    private val library: TemplateLibrary,
    private val settings: AppSettings,
    private val debugDir: File?,
) {
    private val matchers = MatcherRegistry.all()

    class FrameObjects(val frame: Frame, val segMethod: String, val queries: List<List<CanonicalImage>>, val overlay: Mat?)

    /** フレーム群から物体を切り出して canonical 化。ライブプレビュー/登録でも使う。 */
    fun extract(frames: List<Frame>, product: Product, expectedCount: Int, makeOverlay: Boolean): List<FrameObjects> {
        val seg = Segmenter(settings)
        val out = ArrayList<FrameObjects>()
        for (f in frames) {
            val partner = frames.firstOrNull { it.flash != f.flash }
            val s = seg.segment(f, partner, product.background, expectedCount)
            val objs = s.objects.take(expectedCount)
            val overlay = if (makeOverlay) drawOverlay(f.bgr, s, objs.size == expectedCount) else null
            if (objs.size != expectedCount) { s.release(); out += FrameObjects(f, s.method, emptyList(), overlay); continue }
            // 位置で並べる(左→右, 次に上→下)。フレーム間で同じ物体が同じ index になるようにする。
            val sorted = objs.sortedWith(compareBy({ (it.centroid.x / 40).toInt() }, { it.centroid.y }))
            val allowMirror = product.parts.any { it.allowMirror }
            val queries = sorted.map { o ->
                val m = Segmenter.objectMask(s, o)
                val c = Canonicalizer.canonicalize(f.bgr, m, o, settings.canonicalLongEdge)
                m.release()
                c.variants(allowMirror)
            }
            s.release()
            out += FrameObjects(f, s.method, queries, overlay)
        }
        return out
    }

    fun verify(frames: List<Frame>, product: Product): VerificationResult {
        val t0 = System.currentTimeMillis()
        val expected = product.parts.size
        val extracted = extract(frames, product, expected, makeOverlay = settings.saveDebugImages)
        val usable = extracted.filter { it.queries.isNotEmpty() }
        val debug = ArrayList<String>()
        if (settings.saveDebugImages && debugDir != null) {
            extracted.forEachIndexed { i, fo -> fo.overlay?.let { debug += saveDebug("frame${i}_${if (fo.frame.flash) "flash" else "amb"}_${fo.segMethod}", it) } }
        }
        if (usable.isEmpty()) {
            val counts = extracted.map { it.queries.size }
            return VerificationResult(product.id, product.name, expected, extracted.firstOrNull()?.queries?.size ?: 0, emptyList(), false,
                "検出数不一致: ${expected}個の物体が必要ですが、どのフレームでも一致しませんでした (背景/距離/照明を確認)",
                System.currentTimeMillis() - t0, debug)
        }

        val selfParts = library.partsOf(product.id).filter { it.views.isNotEmpty() }
        if (selfParts.size != expected) {
            return VerificationResult(product.id, product.name, expected, expected, emptyList(), false,
                "テンプレート不足: 全パーツに登録ビューが必要です", System.currentTimeMillis() - t0, debug)
        }
        val impostorParts = library.partsNotOf(product.id)
        val active = Scoring.activeMethods(settings, product, matchers)
        val cheap = active.filter { it.cost <= 1 }
        val expensive = active.filter { it.cost > 1 }

        // scores[segment][partKey][method] = フレームごとの値リスト
        val perSeg = List(expected) { HashMap<String, HashMap<MethodId, MutableList<Float>>>() }
        fun add(seg: Int, key: String, m: MethodId, v: Float) {
            perSeg[seg].getOrPut(key) { HashMap() }.getOrPut(m) { ArrayList() }.add(v)
        }

        // --- 1) 期待製品: 全手法
        for (fo in usable) for ((si, qv) in fo.queries.withIndex()) for (lp in selfParts) {
            val sc = Scoring.scorePart(qv, lp, active, settings)
            sc.forEach { (m, s) -> if (s != null && !s.unavailable) add(si, lp.key, m, s.score) }
        }
        // --- 2) 他製品: 安価な手法を全候補に → 上位 K 候補にだけ高価な手法
        val cheapImp = HashMap<String, MutableList<Float>>()
        for (fo in usable) for ((si, qv) in fo.queries.withIndex()) for (lp in impostorParts) {
            val sc = Scoring.scorePart(qv, lp, cheap, settings)
            var acc = 0f; var n = 0
            sc.forEach { (m, s) -> if (s != null && !s.unavailable) { add(si, lp.key, m, s.score); acc += s.score; n++ } }
            if (n > 0) cheapImp.getOrPut(lp.key) { ArrayList() }.add(acc / n)
        }
        val topK = cheapImp.entries.sortedByDescending { it.value.max() }.take(4).map { it.key }.toSet()
        val hardImpostors = impostorParts.filter { it.key in topK || cheap.isEmpty() }
        for (fo in usable) for ((si, qv) in fo.queries.withIndex()) for (lp in hardImpostors) {
            val sc = Scoring.scorePart(qv, lp, expensive, settings)
            sc.forEach { (m, s) -> if (s != null && !s.unavailable) add(si, lp.key, m, s.score) }
        }

        // --- 3) フレーム集約
        fun agg(seg: Int, key: String): Map<MethodId, Float> =
            perSeg[seg][key]?.mapValues { Scoring.aggregate(it.value, settings.frameAggregation) } ?: emptyMap()
        fun fusedOf(scores: Map<MethodId, Float>): Float {
            var w = 0f; var a = 0f
            for ((m, s) in scores) { val c = settings.methods[m] ?: continue; w += c.weight; a += c.weight * s }
            return if (w > 0) a / w else 0f
        }

        // --- 4) 割り当て
        val matrix = Array(expected) { pi -> FloatArray(expected) { si -> fusedOf(agg(si, selfParts[pi].key)) } }
        val assign = Decision.assign(matrix)

        // --- 5) 判定
        val verdicts = ArrayList<PartVerdict>()
        val partsByKey = impostorParts.associateBy { it.key }
        for ((pi, lp) in selfParts.withIndex()) {
            val si = assign[pi]
            val self = agg(si, lp.key)
            // 他製品の最良: 手法ごとに max、名前は総合の最大
            val bestImp = HashMap<MethodId, Float>()
            var bestName = "-"; var bestFused = -1f
            for (ik in perSeg[si].keys) {
                if (ik.startsWith(product.id + "/")) continue
                val sc = agg(si, ik)
                sc.forEach { (m, v) -> bestImp[m] = maxOf(bestImp[m] ?: 0f, v) }
                val f = fusedOf(sc)
                if (f > bestFused) { bestFused = f; bestName = partsByKey[ik]?.displayName ?: ik }
            }
            val outcome = Decision.decide(self, bestImp, impostorParts.isNotEmpty(), settings)
            verdicts += PartVerdict(pi, lp.part.name, si, self, bestImp, bestName, outcome.methodPass,
                outcome.fused, outcome.fusedImpostor, outcome.pass, outcome.reasons)
            if (settings.saveDebugImages && debugDir != null) {
                usable.firstOrNull()?.queries?.getOrNull(si)?.firstOrNull()?.let { debug += saveDebug("seg${si}_part${pi}_canonical", it.bgr) }
            }
        }
        val pass = verdicts.all { it.pass }
        val summary = if (pass) "OK: ${product.name} (${usable.size}/${frames.size} フレーム採用)"
        else "NG: " + verdicts.filter { !it.pass }.joinToString(" / ") { "${it.partName}: ${it.reasons.firstOrNull() ?: "不一致"}" }

        extracted.forEach { fo -> fo.queries.flatten().forEach { it.release() }; fo.overlay?.release() }
        return VerificationResult(product.id, product.name, expected, usable.first().queries.size, verdicts, pass, summary,
            System.currentTimeMillis() - t0, debug)
    }

    private fun drawOverlay(bgr: Mat, seg: jp.hirameq.handycam.imaging.Segmentation, ok: Boolean): Mat {
        val out = bgr.clone()
        val color = if (ok) Scalar(80.0, 220.0, 80.0) else Scalar(60.0, 60.0, 230.0)
        Imgproc.drawContours(out, seg.objects.map { it.contour }, -1, color, 3)
        seg.objects.forEachIndexed { i, o ->
            val pts = arrayOfNulls<org.opencv.core.Point>(4).also { o.rotRect.points(it) }
            for (k in 0 until 4) Imgproc.line(out, pts[k], pts[(k + 1) % 4], Scalar(255.0, 200.0, 0.0), 2)
            Imgproc.putText(out, "#$i", o.centroid, Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, Scalar(255.0, 255.0, 255.0), 2)
        }
        Imgproc.putText(out, seg.method, org.opencv.core.Point(10.0, 30.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, Scalar(255.0, 255.0, 0.0), 2)
        return out
    }

    private fun saveDebug(name: String, img: Mat): String {
        val dir = debugDir ?: return ""
        dir.mkdirs()
        val f = File(dir, "$name.jpg")
        Imgcodecs.imwrite(f.absolutePath, img, org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 80))
        return f.absolutePath
    }

    companion object {
        /** 登録時: 既存ビューがあれば向きを揃え、無ければそのまま返す。 */
        fun orientForRegistration(query: CanonicalImage, part: LoadedPart?): CanonicalImage {
            val ref = part?.views?.firstOrNull()?.image ?: return query
            return Canonicalizer.alignOrientationTo(ref, query, part.part.allowMirror)
        }

        /** 登録時のパーツ割り当て: 既存ビューがあれば HOG+形状で、無ければ左→右の順。 */
        fun assignForRegistration(queries: List<List<CanonicalImage>>, parts: List<LoadedPart>, settings: AppSettings): IntArray {
            val n = parts.size
            if (parts.all { it.views.isEmpty() } || queries.size != n) return IntArray(n) { it }
            val cheap = MatcherRegistry.all().filter { it.id == MethodId.HOG || it.id == MethodId.SHAPE }
            val matrix = Array(n) { pi -> FloatArray(queries.size) { si ->
                if (parts[pi].views.isEmpty()) 0f
                else Scoring.scorePart(queries[si], parts[pi], cheap, settings).values.filterNotNull().filter { !it.unavailable }.map { it.score }.average().toFloat()
            } }
            return Decision.assign(matrix)
        }
    }
}
