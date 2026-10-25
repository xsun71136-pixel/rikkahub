package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多 Key 的密钥列表解析与单 key 固定：
 * 密钥按空白/逗号分隔、去空白去重、保持书写顺序；竞速和测试连接依赖 [withApiKey] 产出的仍是同一具体类型。
 */
class ApiKeySplitTest {

    @Test
    fun `splits keys by whitespace and comma, trims, dedupes, keeps order`() {
        val setting = ProviderSetting.OpenAI(apiKey = "sk-aaa, sk-bbb\nsk-ccc   sk-aaa  ,,")
        assertEquals(listOf("sk-aaa", "sk-bbb", "sk-ccc"), setting.splitApiKeys())
    }

    @Test
    fun `blank apiKey splits to empty list`() {
        assertTrue(ProviderSetting.OpenAI(apiKey = "  \n , ").splitApiKeys().isEmpty())
    }

    @Test
    fun `firstApiKey picks the first parsed key, falls back to trimmed raw`() {
        assertEquals("sk-bbb", ProviderSetting.OpenAI(apiKey = "sk-aaa,sk-bbb").firstApiKey())
        // 解析不出任何 key 时回退到原始字符串的 trim，请求照常发出由服务端报错
        assertEquals("", ProviderSetting.OpenAI(apiKey = "  ").firstApiKey())
    }

    @Test
    fun `withApiKey pins single key and keeps concrete type for all implementations`() {
        val key = "sk-pinned"
        val settings = listOf(
            ProviderSetting.OpenAI(apiKey = "a b"),
            ProviderSetting.Google(apiKey = "a b"),
            ProviderSetting.Claude(apiKey = "a b"),
        )
        settings.forEach { setting ->
            val pinned = setting.withApiKey(key)
            assertEquals(listOf(key), pinned.splitApiKeys())
            // 并发竞速把 pin 结果强转回原类型，类型必须不变
            assertEquals(setting::class, pinned::class)
        }
    }

    @Test
    fun `multiKey config defaults to disabled`() {
        assertTrue(!ProviderSetting.OpenAI().multiKey.enabled)
        assertEquals(MultiKeyStrategy.ROUND_ROBIN, ProviderSetting.OpenAI().multiKey.strategy)
    }
}
