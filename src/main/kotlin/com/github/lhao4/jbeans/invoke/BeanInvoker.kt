package com.github.lhao4.jbeans.invoke

import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.psi.TypeDescriptor
import com.google.gson.Gson
import java.net.URLEncoder

object BeanInvoker {

    private val gson = Gson()

    fun invoke(conn: AgentConnection, meta: MethodMeta, userJson: String): Result<String> {
        val query = buildQuery(meta)
        val argsJson = normalizeArgs(meta, userJson)
        return conn.invoke(query, argsJson)
    }

    private fun buildQuery(meta: MethodMeta): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val paramNames = meta.paramNames.joinToString(",") { enc(it) }
        val paramTypes = meta.paramTypes.joinToString(",") { enc(fqn(it)) }
        return "bean=${enc(meta.classFqn)}&method=${enc(meta.methodName)}" +
                "&paramNames=$paramNames&paramTypes=$paramTypes"
    }

    private fun normalizeArgs(meta: MethodMeta, userJson: String): String {
        if (meta.paramNames.isEmpty()) return "{}"
        if (meta.paramNames.size == 1) {
            // Wrap single-param value into {"paramName": value}
            val raw = gson.fromJson(userJson, Any::class.java)
            return gson.toJson(mapOf(meta.paramNames[0] to raw))
        }
        // Multi-param: ParamGenerator already produces {"p1":v1,"p2":v2,...}
        return userJson
    }

    private fun fqn(t: TypeDescriptor): String = when (t) {
        is TypeDescriptor.Primitive -> t.kind.name.lowercase()
        is TypeDescriptor.Pojo -> t.fqn
        is TypeDescriptor.CollectionType -> "java.util.List"
        is TypeDescriptor.MapType -> "java.util.Map"
        TypeDescriptor.Unknown -> "java.lang.Object"
    }
}
