package com.github.lhao4.jbeans.invoke

import com.github.lhao4.jbeans.process.ProcessSession
import java.io.File
import java.nio.file.Files

object JvmAttachAgent {

    private val loadedPids = mutableSetOf<Int>()

    fun ensureLoaded(session: ProcessSession, port: Int): Result<Unit> {
        if (session.pid in loadedPids) return Result.success(Unit)
        return extractAgentJar().mapCatching { jar ->
            session.loadAgent(jar.absolutePath, "port=$port").getOrThrow()
            loadedPids += session.pid
        }
    }

    fun onSessionDisconnected(pid: Int) {
        loadedPids -= pid
    }

    private fun extractAgentJar(): Result<File> = runCatching {
        val stream = JvmAttachAgent::class.java.getResourceAsStream("/agent/jbeans-agent.jar")
            ?: error("jbeans-agent.jar not found in plugin resources")
        val tmp = Files.createTempFile("jbeans-agent", ".jar").toFile().also { it.deleteOnExit() }
        stream.use { it.copyTo(tmp.outputStream()) }
        tmp
    }
}
