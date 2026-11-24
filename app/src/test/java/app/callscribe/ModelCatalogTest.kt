package app.callscribe

import app.callscribe.providers.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogTest {
    @Test fun geminiKeepsTextModelsOnly() {
        val json = """{"models":[
            {"name":"models/gemini-3.5-flash-lite","displayName":"Gemini 3.5 Flash-Lite","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-embedding-001","displayName":"Embedding","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-nano-banana-2.1","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemma-3-27b-it","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro","supportedGenerationMethods":["generateContent"]}
        ]}"""
        val models = ModelCatalog.parseGemini(json)
        assertEquals(listOf("gemini-2.5-pro", "gemini-3.5-flash-lite"), models.map { it.id })
        assertEquals("Gemini 3.5 Flash-Lite", models[1].name)
        assertNull(models[0].price)
    }

    @Test fun openRouterKeepsStructuredOutputModelsCheapestFirst() {
        val json = """{"data":[
            {"id":"big/model","name":"Big","supported_parameters":["structured_outputs"],"pricing":{"prompt":"0.000003","completion":"0.000015"}},
            {"id":"anthropic/claude-haiku-5.5","name":"Claude Haiku 5.5","supported_parameters":["tools","structured_outputs"],"pricing":{"prompt":"0.0000001","completion":"0.0000005"}},
            {"id":"no/json","name":"No JSON","supported_parameters":["tools"],"pricing":{"prompt":"0","completion":"0"}},
            {"id":"free/model:free","name":"Free","supported_parameters":["structured_outputs"],"pricing":{"prompt":"0","completion":"0"}},
            {"id":"openrouter/auto","name":"Auto","supported_parameters":["structured_outputs"],"pricing":{"prompt":"-1","completion":"-1"}}
        ]}"""
        val models = ModelCatalog.parseOpenRouter(json)
        assertEquals(listOf("free/model:free", "anthropic/claude-haiku-5.5", "big/model", "openrouter/auto"), models.map { it.id })
        assertEquals("free", models[0].price)
        // 2500 × 1e-7 + 300 × 5e-7 = $0.0004
        assertEquals(0.0004, models[1].usdPerCall!!, 1e-9)
        assertEquals("≈ $0.0004 / call", models[1].price)
        assertEquals("≈ $0.012 / call", models[2].price)
        assertNull(models[3].price)
    }
}
