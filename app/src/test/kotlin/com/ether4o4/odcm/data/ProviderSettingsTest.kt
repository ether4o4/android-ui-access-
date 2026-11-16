package com.ether4o4.odcm.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("odcm_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun keepsLegacyClaudeModelWhenSwitchingProviders() {
        context.getSharedPreferences("odcm_settings", Context.MODE_PRIVATE)
            .edit().putString("model", "existing-claude-model").commit()
        val settings = Settings(context)
        assertEquals(ModelProvider.ANTHROPIC, settings.provider)
        assertEquals("existing-claude-model", settings.model)
        settings.provider = ModelProvider.GROQ
        assertEquals(ModelProvider.GROQ.defaultModel, settings.model)
        settings.model = "groq-custom-model"
        settings.provider = ModelProvider.ANTHROPIC
        assertEquals("existing-claude-model", settings.model)
        settings.provider = ModelProvider.GROQ
        assertEquals("groq-custom-model", Settings(context).model)
    }

    @Test fun customEndpointNeverReplacesAnotherProvidersEndpoint() {
        val settings = Settings(context)
        settings.provider = ModelProvider.CUSTOM
        settings.endpoint = "https://example.test/v1/chat/completions"
        settings.provider = ModelProvider.OLLAMA
        assertEquals(ModelProvider.OLLAMA.defaultEndpoint, settings.endpoint)
        settings.provider = ModelProvider.CUSTOM
        assertEquals("https://example.test/v1/chat/completions", settings.endpoint)
    }
}
