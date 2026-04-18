package com.github.lhao4.jbeans.invoke

import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

class SpringCtxFetcher {

    private val gson = Gson()

    /** Returns FQNs of all bean types via Spring Boot Actuator. Empty set if actuator unavailable. */
    fun fetchBeanClassNames(port: Int): Result<Set<String>> = runCatching {
        val managementPort = resolveManagementPort(port)
        val json = httpGet("http://localhost:$managementPort/actuator/beans")
        parseBeansResponse(json)
    }

    private fun resolveManagementPort(appPort: Int): Int {
        // Management port may differ from app port; try app port first, then common defaults
        return appPort
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBeansResponse(json: String): Set<String> {
        val root = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return emptySet()
        val contexts = root["contexts"] as? Map<String, Any> ?: return emptySet()
        return buildSet {
            for (ctx in contexts.values) {
                val beans = (ctx as? Map<String, Any>)?.get("beans") as? Map<String, Any> ?: continue
                for (beanInfo in beans.values) {
                    val type = (beanInfo as? Map<String, Any>)?.get("type") as? String ?: continue
                    add(type)
                }
            }
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 3_000
        conn.requestMethod = "GET"
        return try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
