package com.github.lhao4.jbeans.service

import com.github.lhao4.jbeans.infra.MethodIndexCache
import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.psi.PsiScanner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class MethodSearchService(private val project: Project) {

    private val cache = MethodIndexCache()
    private val indexing = AtomicBoolean(false)

    fun refreshIndex() {
        if (!indexing.compareAndSet(false, true)) return
        DumbService.getInstance(project).runWhenSmart {
            ReadAction.nonBlocking<List<MethodMeta>> {
                PsiScanner(project).scanBeans()
            }.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { methods ->
                cache.updateAll(methods)
                indexing.set(false)
            }.submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
        }
    }

    fun search(query: String): List<MethodMeta> = cache.search(query)

    fun indexSize(): Int = cache.size()

    fun isIndexing(): Boolean = indexing.get()
}
