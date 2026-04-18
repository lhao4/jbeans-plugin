package com.github.lhao4.jbeans.psi

import com.google.gson.GsonBuilder

object ParamGenerator {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun generate(params: List<Pair<String, TypeDescriptor>>): String {
        if (params.isEmpty()) return "{}"
        if (params.size == 1) return gson.toJson(typeToValue(params[0].second))
        val map = linkedMapOf<String, Any?>()
        for ((name, type) in params) map[name] = typeToValue(type)
        return gson.toJson(map)
    }

    fun typeToValue(type: TypeDescriptor): Any? = when (type) {
        is TypeDescriptor.Primitive -> primitiveDefault(type.kind)
        is TypeDescriptor.CollectionType -> listOf(typeToValue(type.element))
        is TypeDescriptor.MapType -> linkedMapOf(typeToValue(type.key) to typeToValue(type.value))
        is TypeDescriptor.Pojo -> linkedMapOf<String, Any?>().also { map ->
            type.fields.forEach { f -> map[f.jsonName] = typeToValue(f.type) }
        }
        TypeDescriptor.Unknown -> null
    }

    private fun primitiveDefault(kind: PrimitiveKind): Any = when (kind) {
        PrimitiveKind.STRING -> ""
        PrimitiveKind.INT -> 0
        PrimitiveKind.LONG -> 0
        PrimitiveKind.BOOLEAN -> false
        PrimitiveKind.DOUBLE -> 0.0
        PrimitiveKind.FLOAT -> 0.0
        PrimitiveKind.BIG_DECIMAL -> 0
        PrimitiveKind.DATE -> "2024-01-01 00:00:00"
    }
}
