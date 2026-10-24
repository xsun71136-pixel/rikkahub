package me.rerere.ai.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `io failures are retried with doubling delays`() = runTest {
        var calls = 0
        val attempts = mutableListOf<RetryAttempt>()

        val result = withRetry(RetryPolicy.NETWORK, onRetry = { attempts += it }) {
            calls++
            if (calls < 3) throw IOException("timeout")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(1, 2), attempts.map { it.retry })
        assertEquals(listOf(1.seconds, 2.seconds), attempts.map { it.delay })
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `gives up after max retries`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IOException("offline")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(4, calls)
    }

    @Test
    fun `other errors are thrown without retrying`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IllegalStateException("bad request")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, calls)
    }

    @Test
    fun `io failure caused by cancellation is not retried`() = runTest {
        var calls = 0

        val job = launch {
            withRetry(RetryPolicy(maxRetries = 3, initialDelay = Duration.ZERO)) {
                calls++
                // 停止生成时，底层连接会以 IOException("Canceled") 结束请求
                currentCoroutineContext().cancel()
                throw IOException("Canceled")
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `custom factor caps backoff at max delay`() = runTest {
        val attempts = mutableListOf<RetryAttempt>()
        var calls = 0

        runCatching {
            withRetry(
                RetryPolicy(
                    maxRetries = 5,
                    initialDelay = 1.seconds,
                    maxDelay = 3.seconds,
                    backoffFactor = 4.0,
                    jitter = false,
                ),
                onRetry = { attempts += it },
            ) {
                calls++
                throw IOException("offline")
            }
        }

        assertEquals(6, calls)
        assertEquals(
            listOf(1.seconds, 3.seconds, 3.seconds, 3.seconds, 3.seconds),
            attempts.map { it.delay },
        )
    }

    @Test
    fun `jitter keeps delay within 20 percent of computed backoff`() = runTest {
        val delays = mutableListOf<Duration>()

        runCatching {
            withRetry(
                RetryPolicy(
                    maxRetries = 5,
                    initialDelay = 10.seconds,
                    backoffFactor = 2.0,
                    jitter = true,
                ),
                onRetry = { delays += it.delay },
            ) {
                throw IOException("offline")
            }
        }

        val expected = listOf(10_000.0, 20_000.0, 30_000.0, 30_000.0, 30_000.0)
        delays.zip(expected).forEach { (actual, base) ->
            val ms = actual.inWholeMilliseconds.toDouble()
            assertTrue(
                "delay $ms should be within ±20% of $base",
                ms >= base * 0.8 - 1 && ms <= base * 1.2 + 1,
            )
        }
    }

    @Test
    fun `response check hit triggers retry`() = runTest {
        var calls = 0

        val result = withRetry(
            RetryPolicy(
                maxRetries = 2,
                initialDelay = Duration.ZERO,
                retryOn = { it is RetryableResponseException },
                responseRetryCheck = { message ->
                    message.toText().takeIf { text -> text.contains("busy") }
                },
            ),
        ) {
            calls++
            if (calls == 1) {
                throw RetryableResponseException("busy")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, calls)
    }
}
