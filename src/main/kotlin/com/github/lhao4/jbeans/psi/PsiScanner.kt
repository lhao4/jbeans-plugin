package com.github.lhao4.jbeans.psi

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch

class PsiScanner(private val project: Project) {

    private val typeResolver = TypeResolver()

    fun scanBeans(): List<MethodMeta> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<MethodMeta>()

        AllClassesSearch.search(scope, project).forEach { psiClass ->
            if (!AnnotationParser.isBeanClass(psiClass)) return@forEach
            val annotations = AnnotationParser.getBeanAnnotationShortNames(psiClass)
            val moduleName = getModuleName(psiClass)

            for (method in psiClass.methods) {
                if (method.isConstructor) continue
                if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue

                val params = method.parameterList.parameters
                results += MethodMeta(
                    className = psiClass.name ?: return@forEach,
                    classFqn = psiClass.qualifiedName ?: return@forEach,
                    methodName = method.name,
                    paramNames = params.map { it.name },
                    paramTypes = params.map { typeResolver.resolve(it.type) },
                    returnType = typeResolver.resolve(method.returnType ?: return@forEach),
                    beanAnnotations = annotations,
                    moduleName = moduleName,
                )
            }
        }
        return results
    }

    private fun getModuleName(psiClass: com.intellij.psi.PsiClass): String? =
        ModuleUtilCore.findModuleForPsiElement(psiClass)?.name
}
