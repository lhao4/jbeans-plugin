package com.github.lhao4.jbeans.psi

import com.intellij.psi.*

class TypeResolver {

    private val MAX_DEPTH = 5

    fun resolve(type: PsiType, depth: Int = 0): TypeDescriptor {
        if (depth > MAX_DEPTH) return TypeDescriptor.Unknown
        return when {
            type == PsiTypes.voidType() -> TypeDescriptor.Unknown
            type is PsiPrimitiveType -> resolvePrimitive(type)
            type is PsiArrayType -> TypeDescriptor.CollectionType(resolve(type.componentType, depth + 1))
            type is PsiClassType -> resolveClassType(type, depth)
            else -> TypeDescriptor.Unknown
        }
    }

    private fun resolvePrimitive(type: PsiPrimitiveType): TypeDescriptor {
        val kind = when (type) {
            PsiTypes.intType(), PsiTypes.byteType(), PsiTypes.shortType() -> PrimitiveKind.INT
            PsiTypes.longType() -> PrimitiveKind.LONG
            PsiTypes.booleanType() -> PrimitiveKind.BOOLEAN
            PsiTypes.doubleType() -> PrimitiveKind.DOUBLE
            PsiTypes.floatType() -> PrimitiveKind.FLOAT
            else -> return TypeDescriptor.Unknown
        }
        return TypeDescriptor.Primitive(kind)
    }

    private fun resolveClassType(type: PsiClassType, depth: Int): TypeDescriptor {
        val psiClass = type.resolve() ?: return TypeDescriptor.Unknown
        val fqn = psiClass.qualifiedName ?: return TypeDescriptor.Unknown

        if (fqn == "java.lang.Object" || fqn == "java.lang.Void") return TypeDescriptor.Unknown

        PRIMITIVE_MAP[fqn]?.let { return TypeDescriptor.Primitive(it) }

        if (isAssignableTo(psiClass, "java.lang.Iterable") && fqn != "java.lang.String") {
            val elem = type.parameters.firstOrNull()?.let { resolve(it, depth + 1) } ?: TypeDescriptor.Unknown
            return TypeDescriptor.CollectionType(elem)
        }

        if (isAssignableTo(psiClass, "java.util.Map")) {
            val key = type.parameters.getOrNull(0)?.let { resolve(it, depth + 1) } ?: TypeDescriptor.Unknown
            val value = type.parameters.getOrNull(1)?.let { resolve(it, depth + 1) } ?: TypeDescriptor.Unknown
            return TypeDescriptor.MapType(key, value)
        }

        if (depth >= MAX_DEPTH) return TypeDescriptor.Pojo(fqn, emptyList())
        return TypeDescriptor.Pojo(fqn, resolvePojoFields(psiClass, depth))
    }

    private fun resolvePojoFields(psiClass: PsiClass, depth: Int): List<FieldDescriptor> = buildList {
        var current: PsiClass? = psiClass
        while (current != null && current.qualifiedName != "java.lang.Object") {
            for (field in current.fields) {
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue
                if (field.hasModifierProperty(PsiModifier.TRANSIENT)) continue
                val jsonName = getJsonPropertyName(field) ?: field.name
                add(FieldDescriptor(field.name, resolve(field.type, depth + 1), jsonName))
            }
            current = current.superClass
        }
    }

    private fun getJsonPropertyName(field: PsiField): String? {
        val ann = field.getAnnotation("com.fasterxml.jackson.annotation.JsonProperty")
            ?: field.getAnnotation("com.alibaba.fastjson.annotation.JSONField")
            ?: field.getAnnotation("com.alibaba.fastjson2.annotation.JSONField")
            ?: return null
        return ann.findAttributeValue("value")?.text?.trim('"')?.takeIf { it.isNotBlank() }
    }

    private fun isAssignableTo(psiClass: PsiClass, targetFqn: String): Boolean {
        val scope = psiClass.resolveScope
        val target = JavaPsiFacade.getInstance(psiClass.project).findClass(targetFqn, scope) ?: return false
        return psiClass == target || psiClass.isInheritor(target, true)
    }

    companion object {
        private val PRIMITIVE_MAP = mapOf(
            "java.lang.String" to PrimitiveKind.STRING,
            "java.lang.CharSequence" to PrimitiveKind.STRING,
            "java.lang.Integer" to PrimitiveKind.INT,
            "java.lang.Short" to PrimitiveKind.INT,
            "java.lang.Byte" to PrimitiveKind.INT,
            "java.lang.Long" to PrimitiveKind.LONG,
            "java.lang.Boolean" to PrimitiveKind.BOOLEAN,
            "java.lang.Double" to PrimitiveKind.DOUBLE,
            "java.lang.Float" to PrimitiveKind.FLOAT,
            "java.lang.Number" to PrimitiveKind.INT,
            "java.math.BigDecimal" to PrimitiveKind.BIG_DECIMAL,
            "java.math.BigInteger" to PrimitiveKind.LONG,
            "java.util.Date" to PrimitiveKind.DATE,
            "java.time.LocalDate" to PrimitiveKind.DATE,
            "java.time.LocalDateTime" to PrimitiveKind.DATE,
            "java.time.LocalTime" to PrimitiveKind.DATE,
        )
    }
}
