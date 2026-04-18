package com.github.lhao4.jbeans.http

import com.github.lhao4.jbeans.psi.TypeDescriptor

data class RouteInfo(
    val httpMethod: String,
    val path: String,
    val className: String,
    val classFqn: String,
    val methodName: String,
    val pathVariables: List<String>,
    val queryParams: List<QueryParam>,
    val requestBody: TypeDescriptor?,
    val moduleName: String?,
) {
    val display: String get() = "${httpMethod.padEnd(7)} $path"
}

data class QueryParam(val name: String, val required: Boolean)
