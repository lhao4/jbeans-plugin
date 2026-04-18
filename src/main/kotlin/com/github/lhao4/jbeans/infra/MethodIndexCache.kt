package com.github.lhao4.jbeans.infra

import com.github.lhao4.jbeans.psi.MethodMeta
import java.util.concurrent.CopyOnWriteArrayList

class MethodIndexCache {

    private val methods = CopyOnWriteArrayList<MethodMeta>()

    fun updateAll(newMethods: List<MethodMeta>) {
        methods.clear()
        methods.addAll(newMethods)
    }

    fun size(): Int = methods.size

    fun search(query: String): List<MethodMeta> {
        if (query.isBlank()) return methods.take(MAX_RESULTS)
        val lower = query.lowercase()
        return methods
            .filter { it.methodName.contains(lower, ignoreCase = true) || it.className.contains(lower, ignoreCase = true) }
            .sortedWith(compareBy(
                { !it.methodName.startsWith(lower, ignoreCase = true) },
                { !it.className.startsWith(lower, ignoreCase = true) },
                { it.className },
                { it.methodName },
            ))
            .take(MAX_RESULTS)
    }

    companion object {
        private const val MAX_RESULTS = 100
    }
}
