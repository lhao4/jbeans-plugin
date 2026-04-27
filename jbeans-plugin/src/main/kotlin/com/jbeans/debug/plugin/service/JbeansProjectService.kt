package com.jbeans.debug.plugin.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.messages.Topic
import com.jbeans.debug.plugin.attach.AgentAttachManager
import com.jbeans.debug.plugin.attach.ProcessDiscovery
import com.jbeans.debug.plugin.client.JbeansDebugClient
import com.jbeans.debug.plugin.client.JbeansDebugException
import java.util.*

// ==================== 事件接口 ====================
interface JbeansDataListener {
    fun onConnectionChanged(connected: Boolean, message: String)
    fun onServicesLoaded(services: List<ServiceInfo>)
    fun onMethodSelected(method: MethodInfo?, interfaceName: String?)
    fun onInvokeResult(result: Map<String, Any?>?, error: String?)

    companion object {
        @JvmField
        val TOPIC = Topic.create("JbeansDataEvents", JbeansDataListener::class.java)
    }
}

// ==================== Agent 返回的数据模型 ====================
/**
 * 对应 Agent /services 返回的 JSON 结构。
 */
data class ServiceInfo(
    val interfaceName: String,
    val methods: List<MethodInfo>
)

data class MethodInfo(
    val name: String,
    val parameterNames: List<String>,
    val parameterTypes: List<String>,
    val returnType: String
) {
    /**
     * 界面展示用的方法签名
     */
    val displaySignature: String
        get() {
            val params = parameterNames.zip(parameterTypes).joinToString(", ") { (name, type) ->
                "${simplifyType(type)} $name"
            }
            return "$name($params): ${simplifyType(returnType)}"
        }

    private fun simplifyType(fqcn: String): String {
        val idx = fqcn.lastIndexOf('.')
        return if (idx > 0) fqcn.substring(idx + 1) else fqcn
    }
}

// ==================== 核心 Service ====================
@State(name = "JbeansDebugSettings", storages = [Storage("jbeans-debug.xml")])
@Service(Service.Level.PROJECT)
class JbeansProjectService(private val project: Project) : PersistentStateComponent<JbeansProjectService.State> {

    private val log = Logger.getInstance(JbeansProjectService::class.java)

    data class State(
        var host: String = "127.0.0.1",
        var lastPid: String = ""
    )

    private var myState = State()

    private var services: List<ServiceInfo> = emptyList()
    private var selectedMethod: MethodInfo? = null
    private var selectedInterface: String? = null

    /**
     * 当前选中的方法是否为 Bean 方法（非服务树接口）
     */
    @Volatile
    var isBeanMethod: Boolean = false
        private set

    /**
     * Bean 方法 resolve 返回的参数 schema（供 ParameterEditorPanel 使用）
     */
    @Volatile
    var lastBeanParamSchemas: List<Any?> = emptyList()

    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * invoke 进行中标记 - 心跳检测期间跳过，避免 debug 断点时误判断开
     */
    @Volatile
    var isInvoking: Boolean = false

    @Volatile
    var attachedPid: String? = null
        private set

    /**
     * 心跳定时器: 每 10s 检测 Agent 是否存活
     */
    private var heartbeatTimer: Timer? = null
    private val heartbeatInterval = 10_000L
    private val maxFailures = 2
    @Volatile
    private var consecutiveFailures = 0

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    val host: String get() = myState.host
    val port: Int get() = AgentAttachManager.DEFAULT_PORT

    // ==================== Attach 流程 ====================
    /**
     * 注入 Agent 到目标 JVM 并建立连接。
     *
     * @return 成功消息或抛出异常
     */
    fun attachToProcess(process: ProcessDiscovery.JvmProcessInfo): String {
        val pid = process.pid
        log.info("Attaching to process: $pid (${process.displayName})")

        // 注入 Agent
        AgentAttachManager.attach(pid, port)

        // 等待 Agent HTTP 就绪 + Context 获取
        val maxWait = 15_000L
        val interval = 500L
        val deadline = System.currentTimeMillis() + maxWait
        var lastStatus = ""

        while (System.currentTimeMillis() < deadline) {
            try {
                val healthy = JbeansDebugClient.healthCheck(myState.host, port)
                if (healthy) {
                    isConnected = true
                    attachedPid = pid
                    myState.lastPid = pid
                    startHeartbeat()
                    publish().onConnectionChanged(connected = true, message = "已连接 PID $pid")
                    return "连接成功: ${process.displayName} (PID $pid)"
                }
                lastStatus = "Agent 启动中..."
            } catch (_: Exception) {
                lastStatus = "等待 Agent HTTP 就绪..."
            }
            Thread.sleep(interval)
        }

        throw RuntimeException("连接超时 (${maxWait / 1000}s): $lastStatus")
    }

    /**
     * 断开连接（关闭 Agent HTTP Server）。
     */
    fun disconnect() {
        if (!isConnected) return
        stopHeartbeat()
        try {
            // 调用 Agent shutdown
            val url = "http://${myState.host}:$port/jbeans-debug/shutdown"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            try { conn.inputStream.close() } catch (_: Exception) {}
            conn.disconnect()
        } catch (e: Exception) {
            log.info("Shutdown request failed (may already be stopped): ${e.message}")
        }
        clearConnectionState()
        publish().onConnectionChanged(connected = false, message = "已断开")
    }

    // ==================== 心跳检测 ====================
    private fun startHeartbeat() {
        stopHeartbeat()
        consecutiveFailures = 0
        heartbeatTimer = Timer("jbeans-debug-heartbeat", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (!isConnected) {
                        cancel()
                        return
                    }
                    if (isInvoking) return // invoke 中 JVM 可能在断点暂停，跳过心跳
                    try {
                        val healthy = JbeansDebugClient.healthCheck(myState.host, port)
                        if (healthy) {
                            consecutiveFailures = 0
                        } else {
                            onHeartbeatFailed()
                        }
                    } catch (e: Exception) {
                        onHeartbeatFailed()
                    }
                }
            }, heartbeatInterval, heartbeatInterval)
        }
        log.info("Heartbeat started (interval=${heartbeatInterval}ms, threshold=$maxFailures)")
    }

    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private fun onHeartbeatFailed() {
        consecutiveFailures++
        log.info("Heartbeat failed ($consecutiveFailures/$maxFailures)")
        if (consecutiveFailures >= maxFailures) {
            log.warn("Agent unreachable after $maxFailures heartbeat failures, auto-disconnecting")
            stopHeartbeat()
            clearConnectionState()
            publish().onConnectionChanged(connected = false, message = "连接已断开（目标进程不可达）")
        }
    }

    // ==================== 服务发现 ====================
    /**
     * 从 Agent 获取服务列表。
     */
    fun refreshServices() {
        if (!isConnected) {
            log.warn("Cannot refresh services: not connected")
            return
        }
        try {
            val raw: List<Map<String, Any>> = JbeansDebugClient.listServices(myState.host, port)
            services = raw.map { svc -> parseServiceInfo(svc) }
            log.info("Loaded ${services.size} services from Agent")
            publish().onServicesLoaded(services)
        } catch (e: Exception) {
            log.warn("Failed to load services: ${e.message}")
            services = emptyList()
            publish().onServicesLoaded(emptyList())
        }
    }

    fun getServices(): List<ServiceInfo> = services

    // ==================== 方法选择 ====================
    fun selectMethod(method: MethodInfo?, interfaceName: String?, beanMethod: Boolean = false) {
        selectedMethod = method
        selectedInterface = interfaceName
        isBeanMethod = beanMethod
        publish().onMethodSelected(method, interfaceName)
    }

    fun getSelectedMethod(): MethodInfo? = selectedMethod
    fun getSelectedInterface(): String? = selectedInterface

    // ==================== 方法调用 ====================
    fun invokeMethod(
        interfaceName: String, methodName: String,
        parameterTypes: List<String>, args: List<Any>
    ): Map<String, Any?> {
        isInvoking = true
        return try {
            val result = JbeansDebugClient.invoke(
                myState.host, port,
                interfaceName, methodName, parameterTypes, args
            )
            publish().onInvokeResult(result, error = null)
            result
        } catch (e: JbeansDebugException) {
            val error = "${e.errorCode}: ${e.message}"
            publish().onInvokeResult(result = null, error)
            throw e
        } finally {
            isInvoking = false
        }
    }

    // ==================== 内部方法 ====================
    @Suppress("UNCHECKED_CAST")
    private fun parseServiceInfo(map: Map<String, Any>): ServiceInfo {
        val interfaceName = map["interfaceName"] as? String ?: ""
        val methodsList = map["methods"] as? List<Map<String, Any>> ?: emptyList()
        val methods = methodsList.map { m ->
            MethodInfo(
                name = m["name"] as? String ?: "",
                parameterNames = (m["parameterNames"] as? List<String>) ?: emptyList(),
                parameterTypes = (m["parameterTypes"] as? List<String>) ?: emptyList(),
                returnType = m["returnType"] as? String ?: "void"
            )
        }
        return ServiceInfo(interfaceName, methods)
    }

    private fun clearConnectionState() {
        isConnected = false
        isInvoking = false
        attachedPid = null
        services = emptyList()
        selectedMethod = null
        selectedInterface = null
        isBeanMethod = false
        lastBeanParamSchemas = emptyList()
    }

    private fun publish(): JbeansDataListener =
        project.messageBus.syncPublisher(JbeansDataListener.TOPIC)

    companion object {
        fun getInstance(project: Project): JbeansProjectService =
            project.getService(JbeansProjectService::class.java)
    }
}
