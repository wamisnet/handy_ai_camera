package jp.hirameq.handycam

import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.FusionMode
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.verify.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTest {
    private fun settings(mode: FusionMode) = AppSettings().apply {
        fusion = mode
        overallThreshold = 0.6f; overallMinMargin = 0.08f
        methods[MethodId.SHAPE]!!.apply { threshold = 0.6f; minMargin = 0f; gate = true; weight = 1f }
        methods[MethodId.HOG]!!.apply { threshold = 0.55f; minMargin = 0.05f; weight = 1f }
    }

    @Test fun passesWhenAboveThresholdAndMargin() {
        val o = Decision.decide(mapOf(MethodId.SHAPE to 0.9f, MethodId.HOG to 0.8f), mapOf(MethodId.SHAPE to 0.85f, MethodId.HOG to 0.5f), true, settings(FusionMode.WEIGHTED_WITH_GATES))
        assertTrue(o.reasons.joinToString(), o.pass)
    }

    @Test fun failsOnMarginEvenIfHighScore() {
        // 他製品がほぼ同じスコア → 識別できていないので NG
        val o = Decision.decide(mapOf(MethodId.SHAPE to 0.9f, MethodId.HOG to 0.8f), mapOf(MethodId.SHAPE to 0.9f, MethodId.HOG to 0.79f), true, settings(FusionMode.WEIGHTED_WITH_GATES))
        assertFalse(o.pass)
        assertTrue(o.reasons.any { it.contains("他製品との差") })
    }

    @Test fun marginIgnoredWithoutImpostors() {
        val o = Decision.decide(mapOf(MethodId.SHAPE to 0.9f, MethodId.HOG to 0.8f), emptyMap(), false, settings(FusionMode.WEIGHTED_WITH_GATES))
        assertTrue(o.pass)
        assertTrue(o.reasons.any { it.contains("未登録") })
    }

    @Test fun gateFailureBlocksWeightedPass() {
        val o = Decision.decide(mapOf(MethodId.SHAPE to 0.3f, MethodId.HOG to 0.95f), mapOf(MethodId.SHAPE to 0.1f, MethodId.HOG to 0.2f), true, settings(FusionMode.WEIGHTED_WITH_GATES))
        assertFalse(o.pass)
        val w = Decision.decide(mapOf(MethodId.SHAPE to 0.3f, MethodId.HOG to 0.95f), mapOf(MethodId.SHAPE to 0.1f, MethodId.HOG to 0.2f), true, settings(FusionMode.WEIGHTED))
        assertTrue(w.pass)
    }

    @Test fun assignmentPicksBestPermutation() {
        val m = arrayOf(floatArrayOf(0.2f, 0.9f), floatArrayOf(0.8f, 0.3f))
        assertEquals(listOf(1, 0), Decision.assign(m).toList())
    }
}
