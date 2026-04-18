package com.github.lhao4.jbeans.process

class ProcessFeatureExtractor {

    fun isSpringBoot(features: ProcessFeatures): Boolean =
        features.springAppName != null ||
        features.serverPort != null ||
        features.mainClass.contains("SpringApplication", ignoreCase = true)

    fun buildDisplayLabel(features: ProcessFeatures): String {
        val appName = features.springAppName
            ?: features.mainClass.substringAfterLast('.').ifBlank { "pid:${features.pid}" }
        val port = features.serverPort?.let { ":$it" } ?: ""
        return "$appName$port (${features.pid})"
    }
}
