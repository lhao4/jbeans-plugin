package com.github.lhao4.jbeans.psi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch

class PsiScanner(private val project: Project) {

    private val log = Logger.getInstance(PsiScanner::class.java)
    private val typeResolver = TypeResolver()

    fun scanBeans(): List<MethodMeta> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<MethodMeta>()
        AllClassesSearch.search(scope, project).forEach { psiClass ->
            runCatching { scanClass(psiClass, results) }.onFailure { ex ->
                if (ex is ProcessCanceledException) throw ex
                log.warn("JBeans: skipping class ${psiClass.qualifiedName} during bean scan", ex)
            }
        }
        return results
    }

    private fun scanClass(psiClass: PsiClass, results: MutableList<MethodMeta>) {
        if (!AnnotationParser.isBeanClass(psiClass)) return
        val annotations = AnnotationParser.getBeanAnnotationShortNames(psiClass)
        val moduleName = ModuleUtilCore.findModuleForPsiElement(psiClass)?.name

        for (method in psiClass.methods) {
            if (method.isConstructor) continue
            if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue
            val params = method.parameterList.parameters
            results += MethodMeta(
                className = psiClass.name ?: return,
                classFqn = psiClass.qualifiedName ?: return,
                methodName = method.name,
                paramNames = params.map { it.name },
                paramTypes = params.map { typeResolver.resolve(it.type) },
                returnType = typeResolver.resolve(method.returnType ?: return),
                beanAnnotations = annotations,
                moduleName = moduleName,
            )
        }
    }
}
