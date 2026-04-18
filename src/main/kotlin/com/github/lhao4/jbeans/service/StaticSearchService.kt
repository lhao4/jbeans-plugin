package com.github.lhao4.jbeans.service

import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.psi.StaticMethodScanner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class StaticSearchService(private val project: Project) {

    @Volatile private var methods: List<MethodMeta> = emptyList()
    private val indexing = AtomicBoolean(false)

    fun refreshIndex() {
        if (!indexing.compareAndSet(false, true)) return
        DumbService.getInstance(project).runWhenSmart {
            ReadAction.nonBlocking<List<MethodMeta>> {
                StaticMethodScanner(project).scanMethods()
            }.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { result ->
                methods = result
                indexing.set(false)
            }.submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    fun search(query: String): List<MethodMeta> {
        if (query.isBlank()) return methods
        val q = query.lowercase()
        return methods.filter {
            it.className.lowercase().contains(q) || it.methodName.lowercase().contains(q)
        }
    }

    fun isIndexing(): Boolean = indexing.get()
}
