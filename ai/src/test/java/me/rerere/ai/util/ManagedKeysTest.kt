package me.rerere.ai.util

import me.rerere.ai.provider.ProviderApiKey
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.enableMultiKeyFromCurrentValue
import me.rerere.ai.provider.getProviderApiKeys
import me.rerere.ai.provider.syncEnabledApiKeysToLegacyField
import me.rerere.ai.provider.withSingleApiKeyForRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ManagedKeysTest {
    @Test fun oldSettingsKeepSingleKeyMode() {
        val provider = Json.decodeFromString<ProviderSetting.OpenAI>("""{"apiKey":"old-key"}""")
        assertFalse(provider.multiKeyEnabled)
        assertEquals("old-key", KeyRoulette.default().next(provider))
    }

    @Test fun importDeduplicatesLegacyKeys() {
        val provider = ProviderSetting.OpenAI(apiKey = "one, two\none").enableMultiKeyFromCurrentValue()
        assertEquals(listOf("one", "two"), provider.getProviderApiKeys().map { it.value })
    }

    @Test(expected = AllKeysSuspendedException::class)
    fun disabledPoolNeverUsesStaleLegacyField() {
        KeyRoulette.default().next(ProviderSetting.OpenAI(
            apiKey = "old-secret", multiKeyEnabled = true,
            apiKeys = listOf(ProviderApiKey(value = "disabled", enabled = false)),
        ))
    }

    @Test fun disablingEveryKeyClearsCompatibilityField() {
        val provider = ProviderSetting.OpenAI(
            apiKey = "old-secret", multiKeyEnabled = true,
            apiKeys = listOf(ProviderApiKey(value = "disabled", enabled = false)),
        ).syncEnabledApiKeysToLegacyField() as ProviderSetting.OpenAI
        assertEquals("", provider.apiKey)
    }

    @Test fun roundRobinAndPinnedCopiesStayIndependent() {
        val provider = ProviderSetting.OpenAI(
            multiKeyEnabled = true, keyStrategy = ProviderKeyStrategy.ROUND_ROBIN,
            apiKeys = listOf(ProviderApiKey(value = "one"), ProviderApiKey(value = "two")),
        )
        val roulette = KeyRoulette.default()
        val first = provider.withSingleApiKeyForRequest(roulette.next(provider))
        val second = provider.withSingleApiKeyForRequest(roulette.next(provider))
        assertEquals("one", roulette.next(first))
        assertEquals("two", roulette.next(second))
        assertEquals("one", roulette.next(first))
        assertEquals("one", roulette.next(provider))
    }

    @Test fun quotaAndRateLimitAreDifferent() {
        assertEquals(KeyVerdict.COOLDOWN, KeyHealthRegistry.classify(ProviderHttpException(429, "daily rate quota exceeded")))
        assertEquals(KeyVerdict.QUOTA, KeyHealthRegistry.classify(ProviderHttpException(429, "insufficient_quota")))
        assertEquals(KeyVerdict.INVALID, KeyHealthRegistry.classify(ProviderHttpException(401)))
        assertEquals(KeyVerdict.NEUTRAL, KeyHealthRegistry.classify(ProviderHttpException(403, "model forbidden")))
        assertEquals(KeyVerdict.NEUTRAL, KeyHealthRegistry.classify(ProviderHttpException(503, "upstream invalid api key")))
    }

    @Test fun serviceAccountDoesNotUseManagedKeyPool() {
        val provider = ProviderSetting.Google(multiKeyEnabled = true, vertexAI = true, useServiceAccount = true)
        assertFalse(KeyRotationPolicy.manages(provider))
    }
}
