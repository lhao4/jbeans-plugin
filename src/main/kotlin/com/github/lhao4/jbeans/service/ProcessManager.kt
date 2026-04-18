package com.github.lhao4.jbeans.service

import com.github.lhao4.jbeans.process.JvmScanner
import com.github.lhao4.jbeans.process.ProcessSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ProcessManager(private val project: Project) : Disposable {

    private val scanner = JvmScanner()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jbeans-process-monitor").apply { isDaemon = true }
    }

    private var session: ProcessSession? = null
    private var monitorTask: ScheduledFuture<*>? = null

    private val listeners = mutableListOf<(ProcessSession?) -> Unit>()

    fun addSessionListener(listener: (ProcessSession?) -> Unit) {
        listeners.add(listener)
    }

    fun currentSession(): ProcessSession? = session

    fun scanProcesses() = scanner.scanAll().filter { scanner.isSpringBootProcess(it) }

    fun connect(pid: Int) {
        disconnect()
        val s = ProcessSession(pid)
        s.connect().onSuccess {
            session = s
            startMonitor(s)
            notifyListeners(s)
        }
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
            if (!s.isAlive()) {
                s.disconnect()
                session = null
                monitorTask?.cancel(false)
                monitorTask = null
                notifyListeners(null)
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun notifyListeners(s: ProcessSession?) = listeners.forEach { it(s) }

    override fun dispose() {
        executor.shutdownNow()
        session?.disconnect()
    }
}
