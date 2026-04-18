package com.github.lhao4.jbeans.service

import com.github.lhao4.jbeans.infra.MethodIndexCache
import com.github.lhao4.jbeans.invoke.SpringCtxFetcher
import com.github.lhao4.jbeans.process.ProcessSession
import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.psi.PsiScanner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class MethodSearchService(private val project: Project) {

    private val cache = MethodIndexCache()
    private val indexing = AtomicBoolean(false)

    init {
        val manager = project.getService(ProcessManager::class.java)
        manager.addSessionListener { session ->
            if (session != null) onSessionConnected(session) else cache.clearRuntimeConfirmed()
        }
        // Handle already-connected session at service startup
        manager.currentSession()?.let { onSessionConnected(it) }
    }

    fun refreshIndex() {
        if (!indexing.compareAndSet(false, true)) return
        DumbService.getInstance(project).runWhenSmart {
            ReadAction.nonBlocking<List<MethodMeta>> {
                PsiScanner(project).scanBeans()
            }.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { methods ->
                cache.updateAll(methods)
                indexing.set(false)
            }.submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    fun search(query: String): List<MethodMeta> = cache.search(query)

    fun indexSize(): Int = cache.size()

    fun isIndexing(): Boolean = indexing.get()

    fun hasRuntimeData(): Boolean = cache.hasRuntimeData()

    private fun onSessionConnected(session: ProcessSession) {
        val port = session.getProperty("management.server.port")?.toIntOrNull()
            ?: session.getProperty("server.port")?.toIntOrNull()
            ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            SpringCtxFetcher().fetchBeanClassNames(port)
                .onSuccess { fqns -> if (fqns.isNotEmpty()) cache.setRuntimeConfirmed(fqns) }
        }
    }
}
