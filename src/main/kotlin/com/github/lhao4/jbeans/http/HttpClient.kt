package com.github.lhao4.jbeans.http

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResponse(val statusCode: Int, val body: String, val durationMs: Long)

object HttpClient {

    @Volatile private var activeConn: HttpURLConnection? = null

    fun cancel() { activeConn?.disconnect() }

    fun send(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String> = emptyMap()
    ): Result<HttpResponse> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        activeConn = conn
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json, */*")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        if (body != null && method !in setOf("GET", "DELETE")) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bytes.size.toString())
        }

        val start = System.currentTimeMillis()
        try {
            if (body != null && conn.doOutput) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val responseBody = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
            }.getOrDefault("")
            HttpResponse(code, responseBody, System.currentTimeMillis() - start)
        } finally {
            activeConn = null
            conn.disconnect()
        }
    }

    fun buildUrl(baseUrl: String, path: String, pathVarValues: Map<String, String>, queryParams: Map<String, String>): String {
        var resolvedPath = path
        pathVarValues.forEach { (k, v) -> resolvedPath = resolvedPath.replace("{$k}", URLEncoder.encode(v, "UTF-8")) }

        val base = baseUrl.trimEnd('/') + resolvedPath
        if (queryParams.isEmpty()) return base
        val query = queryParams.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$base?$query"
    }
}
