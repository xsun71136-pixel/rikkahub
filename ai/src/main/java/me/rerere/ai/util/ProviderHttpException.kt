package me.rerere.ai.util

import okhttp3.Response
import java.io.IOException

/** Retain HTTP status even when an SSE error body is empty, HTML or invalid JSON. */
class ProviderHttpException(
    val statusCode: Int,
    detail: String = "",
    cause: Throwable? = null,
) : RuntimeException("HTTP $statusCode: ${detail.take(2048)}", cause)

fun providerStreamFailure(response: Response?, cause: Throwable?): Throwable = when {
    response != null && !response.isSuccessful ->
        ProviderHttpException(response.code, cause?.message.orEmpty(), cause)
    cause != null -> cause
    else -> IOException("Stream failed without an error body")
}
