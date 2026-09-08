package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 独立视觉模型路由决策（[AgentConfig.effectiveVisionModel]）：
 * 开关打开默认仍是主模型，只有单独设置了模型名且本次请求带图才切换。
 */
class AgentConfigVisionModelTest {

    private fun config(
        visionModelEnabled: Boolean = false,
        visionModel: String = "",
        modelName: String = "main-model"
    ): AgentConfig = AgentConfig.Builder()
        .apiKey("sk-test")
        .modelName(modelName)
        .visionModelEnabled(visionModelEnabled)
        .visionModel(visionModel)
        .build()

    @Test
    fun switchOff_alwaysMainModel() {
        assertNull(config(visionModelEnabled = false, visionModel = "vision-model").effectiveVisionModel(hasImages = true))
        assertNull(config(visionModelEnabled = false, visionModel = "vision-model").effectiveVisionModel(hasImages = false))
    }

    @Test
    fun switchOnWithoutModel_fallsBackToMainModel() {
        assertNull(config(visionModelEnabled = true, visionModel = "").effectiveVisionModel(hasImages = true))
        assertNull(config(visionModelEnabled = true, visionModel = "   ").effectiveVisionModel(hasImages = true))
    }

    @Test
    fun switchOnWithModel_onlyImageRequestsRoute() {
        val c = config(visionModelEnabled = true, visionModel = "vision-model")
        assertEquals("vision-model", c.effectiveVisionModel(hasImages = true))
        assertNull(c.effectiveVisionModel(hasImages = false))
    }

    @Test
    fun visionModelSameAsMain_noRouting() {
        assertNull(config(visionModelEnabled = true, visionModel = "main-model").effectiveVisionModel(hasImages = true))
    }

    @Test
    fun visionModel_isTrimmed() {
        val c = config(visionModelEnabled = true, visionModel = "  vision-model  ")
        assertEquals("vision-model", c.effectiveVisionModel(hasImages = true))
    }

    @Test
    fun defaults_offAndEmpty() {
        val c = AgentConfig.Builder().apiKey("sk-test").build()
        assertNull(c.effectiveVisionModel(hasImages = true))
        assertEquals(false, c.visionModelEnabled)
        assertEquals("", c.visionModel)
    }
}
