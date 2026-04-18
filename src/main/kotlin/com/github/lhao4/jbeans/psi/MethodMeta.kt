package com.github.lhao4.jbeans.psi

data class MethodMeta(
    val className: String,
    val classFqn: String,
    val methodName: String,
    val paramNames: List<String>,
    val paramTypes: List<TypeDescriptor>,
    val returnType: TypeDescriptor,
    val beanAnnotations: List<String>,
    val moduleName: String?,
) {
    val signature: String get() =
        "$className.$methodName(${paramTypes.joinToString(", ") { typeShortName(it) }})"

    private fun typeShortName(t: TypeDescriptor): String = when (t) {
        is TypeDescriptor.Primitive -> t.kind.name.lowercase()
        is TypeDescriptor.CollectionType -> "List<${typeShortName(t.element)}>"
        is TypeDescriptor.MapType -> "Map<${typeShortName(t.key)}, ${typeShortName(t.value)}>"
        is TypeDescriptor.Pojo -> t.fqn.substringAfterLast('.')
        TypeDescriptor.Unknown -> "?"
    }
}
