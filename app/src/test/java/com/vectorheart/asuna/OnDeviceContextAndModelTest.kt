package com.vectorheart.asuna

import com.vectorheart.asuna.localai.OnDeviceLlmEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceContextAndModelTest {

    @Test
    fun `detectModelMaxTokens корректно определяет размер контекста из имени файла`() {
        assertEquals(4096, OnDeviceLlmEngine.detectModelMaxTokens("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"))
        assertEquals(1280, OnDeviceLlmEngine.detectModelMaxTokens("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"))
        assertEquals(1280, OnDeviceLlmEngine.detectModelMaxTokens("Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"))
        assertEquals(4096, OnDeviceLlmEngine.detectModelMaxTokens("gemma-4-E2B-it-web.task"))
        assertEquals(4096, OnDeviceLlmEngine.detectModelMaxTokens("Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task"))
        assertEquals(2048, OnDeviceLlmEngine.detectModelMaxTokens("unknown_model.task"))
    }

    @Test
    fun `очистка токена HuggingFace от Bearer и пробелов`() {
        fun sanitize(value: String): String =
            value.trim().removePrefix("Bearer ").trim().replace(Regex("[\\r\\n\\t\\s]"), "")

        assertEquals("hf_abc123XYZ", sanitize("Bearer hf_abc123XYZ\n"))
        assertEquals("hf_test999", sanitize("  hf_test999  \r\n"))
        assertEquals("hf_mytoken", sanitize("Bearer   hf_mytoken  "))
    }
}
