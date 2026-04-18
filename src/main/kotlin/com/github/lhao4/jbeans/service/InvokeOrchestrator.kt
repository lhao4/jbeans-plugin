package com.github.lhao4.jbeans.service

import com.github.lhao4.jbeans.invoke.AgentConnection
import com.github.lhao4.jbeans.invoke.BeanInvoker
import com.github.lhao4.jbeans.invoke.JvmAttachAgent
import com.github.lhao4.jbeans.psi.MethodMeta
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.net.ServerSocket

@Service(Service.Level.PROJECT)
class InvokeOrchestrator(private val project: Project) : Disposable {

    private val processManager get() = project.getService(ProcessManager::class.java)
    private var connection: AgentConnection? = null
    private var agentPort: Int = 0

    fun invoke(meta: MethodMeta, userJson: String): Result<String> {
        val session = processManager.currentSession()
            ?: return Result.failure(Exception("No active process session. Please connect to a Spring Boot process first."))

        return ensureAgent().mapCatching { conn ->
            BeanInvoker.invoke(conn, meta, userJson).getOrThrow()
        }
    }

    fun invokeStatic(meta: MethodMeta, userJson: String): Result<String> {
        processManager.currentSession()
            ?: return Result.failure(Exception("No active process session. Please connect to a Spring Boot process first."))

        return ensureAgent().mapCatching { conn ->
            BeanInvoker.invokeStatic(conn, meta, userJson).getOrThrow()
        }
    }

    private fun ensureAgent(): Result<AgentConnection> {
        val existing = connection
        if (existing != null && existing.ping()) return Result.success(existing)

        val port = findFreePort()
        val session = processManager.currentSession()
            ?: return Result.failure(Exception("Session lost"))

        return JvmAttachAgent.ensureLoaded(session, port).mapCatching {
            waitForAgent("localhost", port)
            AgentConnection("localhost", port).also {
                connection = it
                agentPort = port
            }
        }
    }

    private fun waitForAgent(host: String, port: Int) {
        val conn = AgentConnection(host, port)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (conn.ping()) return
            Thread.sleep(150)
        }
        error("Agent did not become ready on port $port within 5 seconds")
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    fun cancelInvoke() { connection?.cancel() }

    override fun dispose() {
        connection = null
    }
}
