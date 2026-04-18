package com.github.lhao4.jbeans.history

import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.psi.TypeDescriptor
import java.util.UUID

data class HistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val signature: String,
    val classFqn: String,
    val className: String,
    val methodName: String,
    val paramNames: List<String>,
    val paramTypeFqns: List<String>,
    val argsJson: String,
    val resultJson: String,
    val success: Boolean,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val starred: Boolean = false
) {
    fun toMethodMeta(): MethodMeta = MethodMeta(
        className = className,
        classFqn = classFqn,
        methodName = methodName,
        paramNames = paramNames,
        paramTypes = paramTypeFqns.map { TypeDescriptor.Pojo(it, emptyList()) },
        returnType = TypeDescriptor.Unknown,
        beanAnnotations = emptyList(),
        moduleName = null
    )

    companion object {
        fun from(
            meta: MethodMeta,
            argsJson: String,
            resultJson: String,
            success: Boolean,
            durationMs: Long
        ) = HistoryRecord(
            signature = meta.signature,
            classFqn = meta.classFqn,
            className = meta.className,
            methodName = meta.methodName,
            paramNames = meta.paramNames,
            paramTypeFqns = meta.paramTypes.map { fqn(it) },
            argsJson = argsJson,
            resultJson = resultJson,
            success = success,
            durationMs = durationMs
        )

        private fun fqn(t: TypeDescriptor): String = when (t) {
            is TypeDescriptor.Primitive -> t.kind.name.lowercase()
            is TypeDescriptor.Pojo -> t.fqn
            is TypeDescriptor.CollectionType -> "java.util.List"
            is TypeDescriptor.MapType -> "java.util.Map"
            TypeDescriptor.Unknown -> "java.lang.Object"
        }
    }
}
