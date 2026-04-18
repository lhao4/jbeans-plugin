package com.github.lhao4.jbeans.service

import com.github.lhao4.jbeans.process.JvmScanner
import com.github.lhao4.jbeans.process.ProcessSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ProcessManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ProcessManager::class.java)
    private val scanner = JvmScanner()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jbeans-process-monitor").apply { isDaemon = true }
    }

    private var session: ProcessSession? = null
    private var monitorTask: ScheduledFuture<*>? = null

    private val listeners = mutableListOf<(ProcessSession?) -> Unit>()

    /** Called by UI when user connects or disconnects a session. */
    var onMonitorDisconnect: (() -> Unit)? = null

    fun addSessionListener(listener: (ProcessSession?) -> Unit) = listeners.add(listener)

    fun currentSession(): ProcessSession? = session

    fun scanProcesses() = scanner.scanAll().filter { scanner.isSpringBootProcess(it) }

    fun setSession(s: ProcessSession?) {
        monitorTask?.cancel(false)
        monitorTask = null
        session = s
        if (s != null) startMonitor(s)
        notifyListeners(s)
    }

    fun disconnect() {
        monitorTask?.cancel(false)
        monitorTask = null
        session?.disconnect()
        session = null
        notifyListeners(null)
    }

    private fun startMonitor(s: ProcessSession) {
        monitorTask = executor.scheduleWithFixedDelay({
            runCatching {
                if (!s.isAlive()) {
                    log.info("JBeans: process ${s.pid} exited, disconnecting")
                    session = null
                    monitorTask?.cancel(false)
                    monitorTask = null
                    notifyListeners(null)
                    onMonitorDisconnect?.invoke()
                }
            }.onFailure { log.warn("JBeans: monitor check failed for pid=${s.pid}", it) }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun notifyListeners(s: ProcessSession?) = listeners.forEach {
        runCatching { it(s) }.onFailure { log.warn("JBeans: session listener threw", it) }
    }

    override fun dispose() {
        executor.shutdownNow()
        session?.disconnect()
    }
}
