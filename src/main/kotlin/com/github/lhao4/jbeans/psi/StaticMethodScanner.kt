package com.github.lhao4.jbeans.psi

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch

class StaticMethodScanner(private val project: Project) {

    private val typeResolver = TypeResolver()

    fun scanMethods(): List<MethodMeta> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<MethodMeta>()

        AllClassesSearch.search(scope, project).forEach { psiClass ->
            if (psiClass.isAnnotationType) return@forEach
            val moduleName = ModuleUtilCore.findModuleForPsiElement(psiClass)?.name

            for (method in psiClass.methods) {
                if (method.isConstructor) continue
                if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue
                if (!method.hasModifierProperty(PsiModifier.STATIC)) continue

                val params = method.parameterList.parameters
                results += MethodMeta(
                    className = psiClass.name ?: continue,
                    classFqn = psiClass.qualifiedName ?: continue,
                    methodName = method.name,
                    paramNames = params.map { it.name },
                    paramTypes = params.map { typeResolver.resolve(it.type) },
                    returnType = typeResolver.resolve(method.returnType ?: continue),
                    beanAnnotations = listOf("static"),
                    moduleName = moduleName,
                )
            }
        }
        return results
    }
}
