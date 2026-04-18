package com.github.lhao4.jbeans.process

import com.sun.tools.attach.VirtualMachine
import com.sun.tools.attach.VirtualMachineDescriptor

data class JvmProcessInfo(
    val pid: Int,
    val mainClass: String,
    val workingDir: String,
    val jvmArgs: List<String>,
    val displayName: String
)

data class ProcessFeatures(
    val pid: Int,
    val workingDir: String,
    val springAppName: String?,
    val dubboAppName: String?,
    val serverPort: Int?,
    val mainClass: String
)

class JvmScanner {

    fun scanAll(): List<JvmProcessInfo> =
        VirtualMachine.list().mapNotNull { desc ->
            runCatching { toProcessInfo(desc) }.getOrNull()
        }

    fun extractFeatures(pid: Int): ProcessFeatures {
        val vm = VirtualMachine.attach(pid.toString())
        return try {
            val props = vm.systemProperties
            ProcessFeatures(
                pid = pid,
                workingDir = props.getProperty("user.dir", ""),
                springAppName = props.getProperty("spring.application.name"),
                dubboAppName = props.getProperty("dubbo.application.name"),
                serverPort = props.getProperty("server.port")?.toIntOrNull(),
                mainClass = props.getProperty("sun.java.command", "").substringBefore(" ")
            )
        } finally {
            vm.detach()
        }
    }

    fun isSpringBootProcess(info: JvmProcessInfo): Boolean =
        SPRING_BOOT_INDICATORS.any { info.displayName.contains(it, ignoreCase = true) }

    private fun toProcessInfo(desc: VirtualMachineDescriptor): JvmProcessInfo {
        val displayName = desc.displayName()
        val mainClass = displayName.substringBefore(" ").ifBlank { displayName }
        return JvmProcessInfo(
            pid = desc.id().toInt(),
            mainClass = mainClass,
            workingDir = "",
            jvmArgs = emptyList(),
            displayName = displayName
        )
    }

    companion object {
        private val SPRING_BOOT_INDICATORS = listOf(
            "org.springframework.boot",
            "SpringApplication",
            "spring-boot"
        )
    }
}
