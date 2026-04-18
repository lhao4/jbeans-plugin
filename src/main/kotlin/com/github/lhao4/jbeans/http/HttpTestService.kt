package com.github.lhao4.jbeans.http

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class HttpTestService(private val project: Project) {

    @Volatile private var routes: List<RouteInfo> = emptyList()
    private val scanning = AtomicBoolean(false)
    private val listeners = mutableListOf<() -> Unit>()

    fun refresh() {
        if (!scanning.compareAndSet(false, true)) return
        DumbService.getInstance(project).runWhenSmart {
            ReadAction.nonBlocking<List<RouteInfo>> {
                RouteScanner(project).scanRoutes()
            }.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { result ->
                routes = result
                scanning.set(false)
                listeners.forEach { it() }
            }.submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    fun search(query: String): List<RouteInfo> {
        if (query.isBlank()) return routes
        val q = query.lowercase()
        return routes.filter { it.path.lowercase().contains(q) || it.className.lowercase().contains(q) }
    }

    fun isScanning(): Boolean = scanning.get()

    fun addListener(l: () -> Unit) = listeners.add(l)
}
