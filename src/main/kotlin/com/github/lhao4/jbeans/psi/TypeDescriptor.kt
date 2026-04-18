package com.github.lhao4.jbeans.psi

sealed class TypeDescriptor {
    data class Primitive(val kind: PrimitiveKind) : TypeDescriptor()
    data class CollectionType(val element: TypeDescriptor) : TypeDescriptor()
    data class MapType(val key: TypeDescriptor, val value: TypeDescriptor) : TypeDescriptor()
    data class Pojo(val fqn: String, val fields: List<FieldDescriptor>) : TypeDescriptor()
    object Unknown : TypeDescriptor()
}

enum class PrimitiveKind { STRING, INT, LONG, BOOLEAN, DOUBLE, FLOAT, BIG_DECIMAL, DATE }

data class FieldDescriptor(val name: String, val type: TypeDescriptor, val jsonName: String = name)
