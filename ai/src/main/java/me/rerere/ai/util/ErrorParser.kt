package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/**
 * HTTP 请求失败。[code] 是响应状态码，拿不到（比如连接阶段就失败）时为 null；
 * 自动重试按状态码匹配和多 Key 的暂停规则都依赖它。
 */
class HttpException(
    message: String,
    val code: Int? = null,
) : RuntimeException(message)

/**
 * 非 2xx 响应统一转成带状态码的 [HttpException]：优先解析错误体里的 message，
 * 解析不了就保留原始体文本。自动重试的状态码匹配和多 Key 的暂停规则都依赖这个码。
 */
fun httpError(response: Response, prefix: String): HttpException {
    val bodyRaw = response.body?.stringSafe().orEmpty()
    if (bodyRaw.isNotBlank()) {
        runCatching { Json.parseToJsonElement(bodyRaw).parseErrorDetail(response.code) }
            .getOrNull()
            ?.let { return it }
    }
    return HttpException("$prefix: ${response.code} $bodyRaw".trim(), response.code)
}

fun JsonElement.parseErrorDetail(code: Int? = null): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail(code)
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this), code)
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array", code)
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail(code)
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content, code)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this), code)
        }
    }
}
