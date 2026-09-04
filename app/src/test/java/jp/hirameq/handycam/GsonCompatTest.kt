package jp.hirameq.handycam

import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.store.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GsonCompatTest {
    private val gson = SettingsStore.gsonWithDefaults()

    @Test fun missingFieldsKeepDefaults() {
        // 古いバージョンの設定 JSON(フィールド欠落)を読んでも null にならない
        val s = gson.fromJson("""{"framesPerStep":3}""", AppSettings::class.java)
        assertEquals(3, s.framesPerStep)
        assertNotNull(s.flashSequence)
        assertNotNull(s.methods)
        assertEquals("MEDIAN", s.frameAggregation)
        assertTrue(s.methods.containsKey(MethodId.SHAPE))
    }

    @Test fun productRoundTrip() {
        val p = jp.hirameq.handycam.store.ProductStore.newProduct("テスト", jp.hirameq.handycam.model.ProductKind.FOAM_PAIR)
        p.parts[0].rois += jp.hirameq.handycam.model.Roi(0.1f, 0.2f, 0.3f, 0.4f, 3f, "押印")
        val json = gson.toJson(p)
        val back = gson.fromJson(json, Product::class.java)
        assertEquals(p.id, back.id)
        assertEquals(2, back.parts.size)
        assertEquals(3f, back.parts[0].rois[0].weight)
        assertEquals(jp.hirameq.handycam.model.BackgroundKind.BLACK, back.background)
    }

    @Test fun settingsRoundTripKeepsEnumMapKeys() {
        val s = AppSettings()
        s.methods[MethodId.FEATURE]!!.weight = 3.5f
        val back = gson.fromJson(gson.toJson(s), AppSettings::class.java)
        assertEquals(3.5f, back.methods[MethodId.FEATURE]!!.weight)
    }
}
