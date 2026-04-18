package com.github.lhao4.jbeans.infra

import com.github.lhao4.jbeans.psi.MethodMeta
import java.util.concurrent.CopyOnWriteArrayList

class MethodIndexCache {

    private val methods = CopyOnWriteArrayList<MethodMeta>()

    @Volatile private var confirmedFqns: Set<String>? = null

    fun updateAll(newMethods: List<MethodMeta>) {
        methods.clear()
        methods.addAll(newMethods)
    }

    fun setRuntimeConfirmed(fqns: Set<String>) {
        confirmedFqns = fqns
    }

    fun clearRuntimeConfirmed() {
        confirmedFqns = null
    }

    fun hasRuntimeData(): Boolean = confirmedFqns != null

    fun size(): Int = methods.size

    fun search(query: String): List<MethodMeta> {
        val confirmed = confirmedFqns
        val pool = if (confirmed != null) methods.filter { it.classFqn in confirmed } else methods

        if (query.isBlank()) return pool.take(MAX_RESULTS)
        val lower = query.lowercase()
        return pool
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
