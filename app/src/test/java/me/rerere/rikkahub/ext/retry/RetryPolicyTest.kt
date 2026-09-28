package me.rerere.rikkahub.ext.retry

import kotlinx.coroutines.CancellationException
import me.rerere.ai.util.AllKeysSuspendedException
import me.rerere.ai.util.ProviderHttpException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {
    private val defaults = AutoRetryConfig()

    @Test fun cancellationAndExhaustionNeverRetry() {
        assertFalse(RetryPolicy.shouldRetry(CancellationException("timeout"), defaults))
        assertFalse(RetryPolicy.shouldRetry(IOException("timeout", CancellationException()), defaults))
        assertFalse(RetryPolicy.shouldRetry(AllKeysSuspendedException("test"), defaults))
    }

    @Test fun httpStatusSurvivesIoWrapping() {
        assertFalse(RetryPolicy.shouldRetry(IOException("network", ProviderHttpException(401)), defaults))
        assertTrue(RetryPolicy.shouldRetry(IOException("network", ProviderHttpException(503)), defaults))
    }

    @Test fun supportsLegacyAndTypedHttpErrors() {
        assertTrue(RetryPolicy.shouldRetry(Exception("Failed to get response: 429 rate limit"), defaults))
        assertTrue(RetryPolicy.shouldRetry(ProviderHttpException(500), defaults))
        assertFalse(RetryPolicy.shouldRetry(ProviderHttpException(400, "try again"), defaults))
    }

    @Test fun insufficientQuotaStopsButRateQuotaDoesNot() {
        assertFalse(RetryPolicy.shouldRetry(ProviderHttpException(429, "insufficient_quota"), defaults))
        assertTrue(RetryPolicy.shouldRetry(ProviderHttpException(429, "rate quota exceeded"), defaults))
    }

    @Test fun transportSettingDoesNotDisableHttpPolicy() {
        val config = defaults.copy(retryOnNetworkError = false)
        assertFalse(RetryPolicy.shouldRetry(IOException("timeout"), config))
        assertTrue(RetryPolicy.shouldRetry(ProviderHttpException(503), config))
    }

    @Test fun backoffAndImportedBoundsAreFinite() {
        val config = defaults.copy(jitter = false)
        assertEquals(1000L, RetryPolicy.backoffDelay(0, config))
        assertEquals(2000L, RetryPolicy.backoffDelay(1, config))
        assertEquals(30000L, RetryPolicy.backoffDelay(1000, config))
        val invalid = defaults.copy(maxRetries = 999, multiplier = Double.NaN, initialDelayMs = Long.MAX_VALUE)
        assertEquals(10, invalid.clamped().maxRetries)
        assertEquals(10000L, invalid.clamped().initialDelayMs)
        assertTrue(invalid.clamped().multiplier.isFinite())
        assertFalse(defaults.retryAfterPartialResponse)
    }
}
