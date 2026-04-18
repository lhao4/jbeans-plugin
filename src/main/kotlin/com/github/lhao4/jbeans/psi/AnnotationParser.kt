package com.github.lhao4.jbeans.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass

object AnnotationParser {

    val BEAN_ANNOTATIONS = setOf(
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Component",
        "org.springframework.stereotype.Repository",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Controller",
        "org.springframework.context.annotation.Bean",
        "com.alibaba.dubbo.config.annotation.DubboService",
        "org.apache.dubbo.config.annotation.DubboService",
        "com.alibaba.dubbo.config.annotation.Service",
        "org.apache.dubbo.config.annotation.Service",
    )

    fun isBeanClass(psiClass: PsiClass): Boolean =
        BEAN_ANNOTATIONS.any { psiClass.hasAnnotation(it) }

    fun getBeanAnnotationShortNames(psiClass: PsiClass): List<String> =
        BEAN_ANNOTATIONS.mapNotNull { fqn ->
            psiClass.getAnnotation(fqn)?.let { fqn.substringAfterLast('.') }
        }

    fun getAnnotationStringValue(annotation: PsiAnnotation, attr: String = "value"): String? =
        annotation.findAttributeValue(attr)
            ?.text
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "null" }
}
