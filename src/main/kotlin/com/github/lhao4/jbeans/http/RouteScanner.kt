package com.github.lhao4.jbeans.http

import com.github.lhao4.jbeans.psi.TypeDescriptor
import com.github.lhao4.jbeans.psi.TypeResolver
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch

class RouteScanner(private val project: Project) {

    private val log = Logger.getInstance(RouteScanner::class.java)
    private val typeResolver = TypeResolver()

    fun scanRoutes(): List<RouteInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<RouteInfo>()
        AllClassesSearch.search(scope, project).forEach { psiClass ->
            runCatching { scanClass(psiClass, results) }.onFailure { ex ->
                if (ex is ProcessCanceledException) throw ex
                log.warn("JBeans: skipping class ${psiClass.qualifiedName} during route scan", ex)
            }
        }
        return results
    }

    private fun scanClass(psiClass: PsiClass, results: MutableList<RouteInfo>) {
        if (!isController(psiClass)) return
        val classPath = classMappingPath(psiClass)
        val moduleName = ModuleUtilCore.findModuleForPsiElement(psiClass)?.name

        for (method in psiClass.methods) {
            if (method.isConstructor) continue
            if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue

            val (httpMethod, methodPath) = methodMapping(method) ?: continue
            val fullPath = joinPaths(classPath, methodPath)

            val pathVars = mutableListOf<String>()
            val queryParams = mutableListOf<QueryParam>()
            var requestBody: TypeDescriptor? = null

            for (param in method.parameterList.parameters) {
                when {
                    param.hasAnnotation(PATH_VARIABLE) -> {
                        val name = annotationName(param, PATH_VARIABLE) ?: param.name
                        pathVars += name
                    }
                    param.hasAnnotation(REQUEST_PARAM) -> {
                        val name = annotationName(param, REQUEST_PARAM) ?: param.name
                        val required = param.getAnnotation(REQUEST_PARAM)
                            ?.findAttributeValue("required")?.text?.toBooleanStrictOrNull() ?: true
                        queryParams += QueryParam(name, required)
                    }
                    param.hasAnnotation(REQUEST_BODY) -> {
                        requestBody = typeResolver.resolve(param.type)
                    }
                }
            }

            results += RouteInfo(
                httpMethod = httpMethod,
                path = fullPath,
                className = psiClass.name ?: return,
                classFqn = psiClass.qualifiedName ?: return,
                methodName = method.name,
                pathVariables = pathVars,
                queryParams = queryParams,
                requestBody = requestBody,
                moduleName = moduleName,
            )
        }
    }

    private fun isController(psiClass: PsiClass) =
        psiClass.hasAnnotation(CONTROLLER) || psiClass.hasAnnotation(REST_CONTROLLER)

    private fun classMappingPath(psiClass: PsiClass): String {
        val ann = psiClass.getAnnotation(REQUEST_MAPPING) ?: return ""
        return extractPath(ann)
    }

    private fun methodMapping(method: PsiMethod): Pair<String, String>? {
        for ((fqn, verb) in METHOD_MAPPINGS) {
            val ann = method.getAnnotation(fqn) ?: continue
            return verb to extractPath(ann)
        }
        val ann = method.getAnnotation(REQUEST_MAPPING) ?: return null
        val methodText = ann.findAttributeValue("method")?.text ?: ""
        val verb = METHOD_MAPPINGS.values.firstOrNull { methodText.contains(it) } ?: "GET"
        return verb to extractPath(ann)
    }

    private fun extractPath(ann: PsiAnnotation): String {
        val raw = ann.findAttributeValue("value")?.text
            ?: ann.findAttributeValue("path")?.text
            ?: return ""
        return firstStringLiteral(raw)
    }

    private fun firstStringLiteral(text: String): String {
        val t = text.trim()
        val inner = if (t.startsWith("{")) t.drop(1).dropLast(1).split(",").first().trim() else t
        return inner.trim('"').takeIf { it.isNotBlank() && it != "null" } ?: ""
    }

    private fun annotationName(param: PsiParameter, fqn: String): String? {
        val ann = param.getAnnotation(fqn) ?: return null
        return (ann.findAttributeValue("value") ?: ann.findAttributeValue("name"))
            ?.text?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun joinPaths(base: String, tail: String): String {
        val b = base.trimEnd('/')
        val t = tail.trimStart('/')
        val joined = if (t.isEmpty()) b else "$b/$t"
        return if (joined.startsWith("/")) joined else "/$joined"
    }

    companion object {
        private const val CONTROLLER = "org.springframework.stereotype.Controller"
        private const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
        private const val REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
        private const val PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable"
        private const val REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam"
        private const val REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"

        private val METHOD_MAPPINGS = linkedMapOf(
            "org.springframework.web.bind.annotation.GetMapping" to "GET",
            "org.springframework.web.bind.annotation.PostMapping" to "POST",
            "org.springframework.web.bind.annotation.PutMapping" to "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
        )
    }
}
