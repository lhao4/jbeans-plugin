package com.github.lhao4.jbeans.invoke

import java.net.HttpURLConnection
import java.net.URL

class AgentConnection(val host: String, val port: Int) {

    fun ping(): Boolean = runCatching {
        val conn = URL("http://$host:$port/ping").openConnection() as HttpURLConnection
        conn.connectTimeout = 500
        conn.readTimeout = 500
        conn.requestMethod = "GET"
        try { conn.responseCode == 200 } finally { conn.disconnect() }
    }.getOrDefault(false)

    fun invoke(query: String, argsJson: String): Result<String> = runCatching {
        val conn = URL("http://$host:$port/invoke?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = argsJson.toByteArray(Charsets.UTF_8)
        conn.setRequestProperty("Content-Length", body.size.toString())
        try {
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) error("Agent HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
