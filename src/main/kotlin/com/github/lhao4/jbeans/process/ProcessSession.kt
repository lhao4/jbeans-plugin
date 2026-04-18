package com.github.lhao4.jbeans.process

import com.sun.tools.attach.VirtualMachine

class ProcessSession(val pid: Int) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    private var vm: VirtualMachine? = null

    fun connect(): Result<Unit> {
        state = State.CONNECTING
        return runCatching {
            vm = VirtualMachine.attach(pid.toString())
            state = State.CONNECTED
        }.onFailure {
            state = State.FAILED
        }
    }

    fun disconnect() {
        runCatching { vm?.detach() }
        vm = null
        state = State.DISCONNECTED
    }

    fun getProperty(key: String): String? =
        runCatching { vm?.systemProperties?.getProperty(key) }.getOrNull()

    fun loadAgent(agentPath: String, options: String): Result<Unit> = runCatching {
        requireNotNull(vm) { "Not connected" }
        vm!!.loadAgent(agentPath, options)
    }

    fun isAlive(): Boolean = ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)
}
