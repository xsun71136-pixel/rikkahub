package me.rerere.ai.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MultiKeyPauseRuleTest {
    private val rules = listOf(
        "429 insufficient_quota",
        "401 invalid_api_key",
        "rate_limit_exceeded",
    )

    @Test
    fun `matches rule with status code and message tokens`() {
        val error = HttpException("insufficient_quota: 当前分组额度已用完", code = 429)
        assertTrue(MultiKeyStateCenter.matchesAnyRule(rules, error))
    }

    @Test
    fun `status code alone does not match rule requiring message token`() {
        val error = HttpException("rate limit reached", code = 429)
        // 命中 "rate_limit_exceeded" 需要完整 token；普通 rate limit 文本不命中任何规则
        assertFalse(MultiKeyStateCenter.matchesAnyRule(rules, error))
    }

    @Test
    fun `matches text-only rule without status code`() {
        val error = HttpException("error code: rate_limit_exceeded", code = null)
        assertTrue(MultiKeyStateCenter.matchesAnyRule(rules, error))
    }

    @Test
    fun `non-http error only matches by message text`() {
        assertFalse(MultiKeyStateCenter.matchesAnyRule(rules, IOException("timeout")))
        assertTrue(
            MultiKeyStateCenter.matchesAnyRule(
                listOf("超时", "timeout"),
                IOException("Socket timeout"),
            ),
        )
    }

    @Test
    fun `blank message never matches`() {
        assertFalse(MultiKeyStateCenter.matchesAnyRule(rules, HttpException("", code = 500)))
    }
}
