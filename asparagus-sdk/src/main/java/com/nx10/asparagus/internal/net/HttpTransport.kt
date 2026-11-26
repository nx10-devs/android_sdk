package com.nx10.asparagus.internal.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return url == other.url &&
            method == other.method &&
            headers == other.headers &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return statusCode == other.statusCode &&
            headers == other.headers &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * Injectable synchronous HTTP transport executed on background I/O dispatchers.
 */
interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * Zero-dependency `HttpURLConnection` implementation of [HttpTransport].
 */
class UrlConnectionHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
) : HttpTransport {

    @Throws(IOException::class)
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.doInput = true

            for ((key, value) in request.headers) {
                connection.setRequestProperty(key, value)
            }

            if (request.body.isNotEmpty() || request.method == "POST" || request.method == "PUT") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { os ->
                    if (request.body.isNotEmpty()) {
                        os.write(request.body)
                    }
                    os.flush()
                }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)

            val responseHeaders = linkedMapOf<String, String>()
            for ((k, values) in connection.headerFields) {
                if (k != null && !values.isNullOrEmpty()) {
                    responseHeaders[k] = values.joinToString(", ")
                }
            }

            return HttpResponse(
                statusCode = statusCode,
                headers = responseHeaders,
                body = responseBody,
            )
        } finally {
            connection.disconnect()
        }
    }
}
