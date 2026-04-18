# JBeans — 架构设计文档（ARCHITECTURE）

**版本**：v1.0  
**日期**：2026-04

---

## 一、整体架构

### 1.1 分层架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 交互层                              │
│   ToolWindow / SearchPanel / InvokePanel / ResultPanel       │
│   语言：Kotlin + IntelliJ UI DSL / Swing                     │
└───────────────────────────┬─────────────────────────────────┘
                            │ 事件 / 命令
┌───────────────────────────▼─────────────────────────────────┐
│                      业务编排层                               │
│   ProcessManager / MethodSearchService / InvokeOrchestrator  │
│   负责：流程协调、状态管理、模块间通信                           │
└──────┬─────────────────────┬────────────────────┬───────────┘
       │                     │                    │
┌──────▼──────┐   ┌──────────▼────────┐  ┌───────▼──────────┐
│  进程管理模块 │   │   静态分析模块     │  │   动态执行模块    │
│             │   │                   │  │                  │
│ JvmScanner  │   │ PsiScanner        │  │ JvmAttachAgent   │
│ ProcessSess │   │ MethodIndexCache  │  │ SpringCtxFetcher │
│ ion         │   │ ParamGenerator    │  │ BeanInvoker      │
└──────┬──────┘   └──────────┬────────┘  └───────┬──────────┘
       │                     │                   │
┌──────▼─────────────────────▼───────────────────▼───────────┐
│                       基础设施层                              │
│   JsonUtils / LogService / StorageService / HttpClient       │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 核心数据流

```
用户选择目标进程
      │
      ▼
JvmScanner 扫描本机 Java 进程
      │
      ▼
ProcessSession 建立连接（JVM Attach）
      │
      ├──────────────────────────────┐
      │                              │
      ▼                              ▼
PsiScanner 静态扫描              SpringCtxFetcher 运行时枚举
（获取参数名、注释、注解）          （获取真实 Bean 实例）
      │                              │
      └──────────────┬───────────────┘
                     │ 合并（类名+方法签名 做 key）
                     ▼
               MethodIndexCache
               （统一方法元数据）
                     │
      用户搜索并选中方法
                     │
                     ▼
               ParamGenerator
               （递归生成 JSON 参数模板）
                     │
      用户填写参数，点击 Invoke
                     │
                     ▼
               BeanInvoker
               （JSON → 入参对象 → 反射调用 → 序列化结果）
                     │
                     ▼
               ResultPanel 展示
```

---

## 二、模块详细设计

### 2.1 进程管理模块

#### JvmScanner — 进程扫描器

```kotlin
class JvmScanner {
    
    // 扫描所有本机 Java 进程
    fun scanAll(): List<JvmProcessInfo>
    
    // 提取进程唯一标识特征
    fun extractFeatures(pid: Int): ProcessFeatures
    
    // 判断是否为 Spring Boot 进程
    fun isSpringBootProcess(info: JvmProcessInfo): Boolean
}

data class JvmProcessInfo(
    val pid: Int,
    val mainClass: String,          // 启动主类
    val workingDir: String,         // 工作目录（关联 IDEA 项目）
    val jvmArgs: List<String>,      // JVM 启动参数
    val displayName: String         // 展示名称
)

data class ProcessFeatures(
    val pid: Int,
    val workingDir: String,
    val springAppName: String?,     // spring.application.name
    val dubboAppName: String?,      // dubbo.application.name
    val serverPort: Int?,           // server.port
    val mainClass: String
)
```

**实现要点**：
- 使用 `VirtualMachine.list()` 获取所有 JVM 进程（来自 tools.jar / JDK attach API）
- 通过 JVM 系统属性读取 spring.application.name 等配置
- Windows 需要特殊处理 attach 权限（同用户进程可直接 attach）

#### ProcessSession — 连接会话管理

```kotlin
class ProcessSession(val pid: Int) {
    
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
    
    var state: State = State.DISCONNECTED
    
    // 建立连接，注入执行 Agent
    fun connect(): Result<Unit>
    
    // 断开连接，清理资源
    fun disconnect()
    
    // 检测进程是否仍存活
    fun isAlive(): Boolean
    
    // 在目标 JVM 中执行指定逻辑（通过 Agent 通信）
    fun execute(command: AgentCommand): AgentResult
}
```

**进程活跃性检测**：
- 后台线程每 5 秒检测一次目标进程状态
- 检测方式：尝试 attach 或 ping Agent socket
- 进程死亡后触发事件通知 UI 更新

---

### 2.2 静态分析模块

#### PsiScanner — PSI 语法树扫描器

```kotlin
class PsiScanner(private val project: Project) {
    
    // 扫描项目所有 Spring Bean 类
    fun scanBeanClasses(): List<BeanClassMeta>
    
    // 扫描指定类的方法元数据
    fun scanMethods(psiClass: PsiClass): List<MethodMeta>
    
    // 递归解析参数类型信息
    fun resolveType(psiType: PsiType): TypeDescriptor
    
    // 提取方法注释（JavaDoc）
    fun extractJavadoc(psiMethod: PsiMethod): String?
}

data class MethodMeta(
    val className: String,          // 全限定类名
    val methodName: String,
    val parameters: List<ParamMeta>,
    val returnType: TypeDescriptor,
    val javadoc: String?,
    val annotations: List<String>,  // 方法上的注解
    val isPublic: Boolean,
    val isStatic: Boolean
)

data class ParamMeta(
    val name: String,               // 参数名（PSI 能拿到）
    val type: TypeDescriptor,
    val annotations: List<String>   // @RequestBody 等
)
```

**PSI 扫描触发时机**：
- 插件首次启动时全量扫描（异步，不阻塞 UI）
- 文件保存时增量更新（监听 PsiTreeChangeListener）
- 用户手动刷新时重新全量扫描

#### TypeDescriptor — 类型描述符

```kotlin
sealed class TypeDescriptor {
    
    // 基础类型：String, int, Long, Boolean...
    data class Primitive(val typeName: String) : TypeDescriptor()
    
    // 集合类型
    data class Collection(
        val collectionType: String,     // List / Set / Array
        val elementType: TypeDescriptor
    ) : TypeDescriptor()
    
    // Map 类型
    data class MapType(
        val keyType: TypeDescriptor,
        val valueType: TypeDescriptor
    ) : TypeDescriptor()
    
    // POJO 对象
    data class Pojo(
        val className: String,
        val fields: List<FieldDescriptor>  // 含父类字段
    ) : TypeDescriptor()
    
    // 泛型参数不确定
    object Unknown : TypeDescriptor()
}
```

#### ParamGenerator — JSON 参数生成器

```kotlin
class ParamGenerator {
    
    // 为方法生成完整的 JSON 参数模板
    fun generate(method: MethodMeta): String
    
    // 递归解析类型，生成 JSON 节点
    private fun generateNode(
        type: TypeDescriptor,
        visitedTypes: MutableSet<String>,  // 防循环引用
        depth: Int                          // 防无限递归
    ): JsonNode
    
    companion object {
        const val MAX_DEPTH = 5             // 最大递归深度
    }
}
```

**默认值规则**：

```kotlin
fun defaultValue(type: TypeDescriptor): Any? = when (type) {
    is Primitive -> when (type.typeName) {
        "String"     -> ""
        "Integer", "int", "Long", "long" -> 0
        "Boolean", "boolean" -> false
        "BigDecimal" -> 0
        "Date"       -> "2024-01-01 00:00:00"
        else         -> null
    }
    is Collection -> listOf(generateNode(type.elementType))
    is MapType    -> mapOf("key" to generateNode(type.valueType))
    is Pojo       -> type.fields.associate { it.jsonName to generateNode(it.type) }
    is Unknown    -> mapOf<String, Any>()
}
```

---

### 2.3 动态执行模块

#### JvmAttachAgent — JVM 动态注入代理

```
┌─────────────────────────────────────────────────────┐
│                   Agent 通信架构                      │
│                                                     │
│  插件进程                    目标 JVM 进程             │
│  ──────────                 ────────────────        │
│  AgentClient  ←──Socket──→  AgentServer             │
│                             （动态注入，内存运行）      │
│                             ↓                       │
│                             SpringCtxFetcher        │
│                             BeanInvoker             │
└─────────────────────────────────────────────────────┘
```

**Agent 注入流程**：

```kotlin
class JvmAttachAgent {
    
    fun attach(pid: Int): AgentConnection {
        // 1. 使用 VirtualMachine.attach(pid) 连接目标进程
        val vm = VirtualMachine.attach(pid.toString())
        
        // 2. 将 Agent Jar 注入目标进程
        //    Agent Jar 打包在插件资源目录中
        //    注入后在目标 JVM 内存中启动一个 Socket Server
        vm.loadAgent(agentJarPath, "port=${findFreePort()}")
        
        // 3. 建立 Socket 连接
        return AgentConnection(host = "127.0.0.1", port = agentPort)
        
        // 注意：vm.detach() 只是断开 attach 连接
        // 注入的 Agent 继续运行，直到目标 JVM 退出
    }
}
```

**Agent Jar 设计**（极简，仅 50~100KB）：

```java
// 运行在目标 JVM 内部
public class InspectorAgent {
    
    public static void agentmain(String args, Instrumentation inst) {
        int port = parsePort(args);
        // 启动 Socket Server，接收来自插件的命令
        new AgentServer(port, inst).start();
    }
}

public class AgentServer {
    // 接收命令：FETCH_CONTEXT / INVOKE_METHOD / LIST_BEANS
    // 执行后返回 JSON 结果
}
```

#### SpringCtxFetcher — Spring 容器抓取器

```kotlin
class SpringCtxFetcher {
    
    // 从目标 JVM 内存中获取所有 Spring ApplicationContext
    fun fetchContexts(connection: AgentConnection): List<ContextInfo>
    
    // 枚举指定 Context 中所有 Bean
    fun listBeans(contextId: String): List<BeanInfo>
    
    // 获取指定 Bean 实例（用于后续调用）
    fun getBean(contextId: String, beanName: String): BeanRef
}

data class BeanInfo(
    val beanName: String,
    val className: String,          // 实际类名（可能是代理类）
    val targetClassName: String,    // 代理时的原始类名
    val isProxy: Boolean,
    val scope: String               // singleton / prototype
)
```

**获取 ApplicationContext 的方案**：

```java
// Agent 内部执行（在目标 JVM 中）
// 方案：遍历所有 Spring ApplicationContext 实现类的实例
// 利用 Instrumentation.getAllLoadedClasses() + 反射

Class<?>[] allClasses = instrumentation.getAllLoadedClasses();
for (Class<?> clazz : allClasses) {
    if (isSpringContextClass(clazz)) {
        // 通过 static field 或 ThreadLocal 获取实例
        // Spring 内部有 ContextLoader.currentWebApplicationContext
        // 或扫描 AbstractApplicationContext 的子类实例
    }
}
```

#### BeanInvoker — Bean 方法调用器

```kotlin
class BeanInvoker {
    
    fun invoke(request: InvokeRequest): InvokeResult
    
    data class InvokeRequest(
        val contextId: String,
        val beanName: String,
        val methodName: String,
        val paramTypes: List<String>,  // 参数类型全限定名，用于方法定位
        val argsJson: String           // 参数 JSON
    )
    
    data class InvokeResult(
        val success: Boolean,
        val resultJson: String?,       // 成功时的返回值
        val exceptionType: String?,    // 失败时的异常类型
        val exceptionMessage: String?,
        val stackTrace: String?,
        val costMs: Long
    )
}
```

**Agent 端调用逻辑（在目标 JVM 中执行）**：

```java
public InvokeResult invoke(InvokeRequest request) {
    long start = System.currentTimeMillis();
    try {
        // 1. 从 ApplicationContext 获取 Bean 实例
        Object bean = applicationContext.getBean(request.beanName);
        
        // 2. 通过方法名 + 参数类型定位具体方法（解决重载）
        Method method = resolveMethod(bean.getClass(), 
                                      request.methodName, 
                                      request.paramTypes);
        
        // 3. private 方法需要 setAccessible
        method.setAccessible(true);
        
        // 4. JSON 反序列化为方法入参
        Object[] args = deserializeArgs(request.argsJson, method);
        
        // 5. 反射调用
        Object result = method.invoke(bean, args);
        
        // 6. 序列化返回值
        return InvokeResult.success(toJson(result), 
                                    System.currentTimeMillis() - start);
        
    } catch (InvocationTargetException e) {
        // 业务异常：从 cause 中提取真实异常
        Throwable cause = e.getCause();
        return InvokeResult.failure(cause, System.currentTimeMillis() - start);
    } catch (Exception e) {
        return InvokeResult.failure(e, System.currentTimeMillis() - start);
    }
}
```

---

### 2.4 HTTP 测试模块

```kotlin
class HttpTestService {
    
    // PSI 扫描提取所有 HTTP 路由
    fun scanRoutes(project: Project): List<HttpRoute>
    
    // 发送 HTTP 请求
    fun send(request: HttpRequest): HttpResponse
    
    data class HttpRoute(
        val method: String,         // GET/POST/PUT/DELETE
        val path: String,           // /api/order/{id}
        val controllerClass: String,
        val handlerMethod: String,
        val params: List<HttpParam>
    )
    
    data class HttpParam(
        val name: String,
        val location: ParamLocation, // BODY/QUERY/PATH/HEADER
        val type: TypeDescriptor,
        val required: Boolean
    )
}
```

---

### 2.5 基础设施层

#### MethodIndexCache — 方法索引缓存

```kotlin
class MethodIndexCache {
    
    // 索引结构：(className + methodSignature) → MethodMeta
    private val index = ConcurrentHashMap<String, MethodMeta>()
    
    // 全文搜索（方法名/类名模糊匹配）
    fun search(keyword: String): List<MethodMeta>
    
    // 合并 PSI 数据（参数名）和运行时数据（Bean 实例引用）
    fun merge(psiMeta: MethodMeta, runtimeBeanInfo: BeanInfo): MethodMeta
    
    // 增量更新（文件修改时）
    fun update(className: String, methods: List<MethodMeta>)
}
```

#### StorageService — 本地持久化

```kotlin
class StorageService {
    
    // 调用历史（最多 200 条）
    fun saveHistory(record: InvokeRecord)
    fun loadHistory(): List<InvokeRecord>
    
    // 收藏的调用
    fun saveFavorite(record: InvokeRecord)
    fun loadFavorites(): List<InvokeRecord>
    
    // 用户配置
    fun saveConfig(config: PluginConfig)
    fun loadConfig(): PluginConfig
}

data class PluginConfig(
    val showPrivateMethods: Boolean = false,
    val maxRecursionDepth: Int = 5,
    val invokeTimeoutMs: Int = 30000,
    val saveHistory: Boolean = true,
    val baseUrl: String = "http://localhost"
)
```

---

## 三、关键技术难点与方案

### 3.1 JDK 版本兼容

| JDK 版本 | 问题 | 解决方案 |
|---------|------|---------|
| JDK 8 | 无模块系统，最简单 | 直接 attach，无限制 |
| JDK 11 | 引入模块系统 | 启动参数加 --add-opens |
| JDK 17 | 强封装，默认拒绝反射私有成员 | setAccessible + --add-opens，或使用 Unsafe |

**统一兼容方案**：

```java
// Agent 注入时动态添加 JVM 参数
// 利用 ByteBuddy 或 Instrumentation 绕过模块限制
instrumentation.redefineModule(
    targetModule,
    Set.of(),
    Map.of("java.lang", Set.of(agentModule)),  // 开放访问
    ...
);
```

### 3.2 类加载器隔离问题

**问题**：Agent 注入的代码和目标项目运行在不同的 ClassLoader 中，Agent 用的 Jackson 和目标项目用的 Jackson 可能版本不同，会发生类冲突。

**解决方案**：

```
Agent Jar 设计原则：
  1. 不引入任何第三方库（零依赖）
  2. JSON 序列化用 JDK 内置能力（javax.json 或手写简单序列化）
  3. 所有类名加特殊前缀，避免与目标项目类名冲突
  4. 使用独立的 URLClassLoader 加载 Agent 类
```

### 3.3 Spring 上下文获取

**问题**：Spring ApplicationContext 没有全局静态入口，获取比较麻烦。

**方案（按优先级）**：

```java
// 方案1：通过 SpringApplication 启动类找 context（最可靠）
// Spring Boot 启动后，context 注册到 SpringApplicationShutdownHook 中
// 通过反射访问 Runtime.getRuntime() 的 shutdown hooks 找到 context

// 方案2：通过 ContextLoader（Web 应用）
WebApplicationContext ctx = ContextLoader.getCurrentWebApplicationContext();

// 方案3：通过 Instrumentation 遍历已加载类
// 找到 AbstractApplicationContext 的子类实例
// 利用 Java Agent 的对象遍历能力（较复杂）

// 方案4：注册一个特殊 Bean（最简单，但需要项目有 @ComponentScan 扫描到 Agent 包）
// 与零侵入原则冲突，不采用
```

### 3.4 方法重载解决

**问题**：同名方法有多个重载版本，需要精确定位。

```kotlin
fun resolveMethod(clazz: Class<*>, name: String, paramTypes: List<String>): Method {
    return clazz.declaredMethods
        .filter { it.name == name }
        .filter { method ->
            // 通过参数类型全限定名精确匹配
            method.parameterTypes.map { it.name } == paramTypes
        }
        .firstOrNull()
        ?: throw MethodNotFoundException("$name($paramTypes)")
}
```

### 3.5 泛型类型擦除

```kotlin
// 反射拿到的可能是擦除后的类型
// 优先从 PSI（源码）读取泛型信息
// 运行时用 Method.getGenericParameterTypes() 作为补充

fun resolveGenericType(method: Method, paramIndex: Int): Type {
    val genericTypes = method.genericParameterTypes
    return genericTypes[paramIndex]  // 返回带泛型信息的 Type
    // 例如：ParameterizedType{ rawType=List, actualArgs=[OrderItem] }
}
```

---

## 四、插件工程结构

```
spring-bean-inspector/
├── src/main/kotlin/
│   ├── ui/                          # UI 交互层
│   │   ├── toolwindow/
│   │   │   ├── InspectorToolWindow.kt
│   │   │   ├── ProcessSelectorPanel.kt
│   │   │   ├── MethodSearchPanel.kt
│   │   │   ├── InvokePanel.kt
│   │   │   └── ResultPanel.kt
│   │   └── actions/
│   │       └── RefreshAction.kt
│   │
│   ├── service/                     # 业务编排层
│   │   ├── ProcessManager.kt
│   │   ├── MethodSearchService.kt
│   │   └── InvokeOrchestrator.kt
│   │
│   ├── process/                     # 进程管理模块
│   │   ├── JvmScanner.kt
│   │   ├── ProcessSession.kt
│   │   └── ProcessFeatureExtractor.kt
│   │
│   ├── psi/                         # 静态分析模块
│   │   ├── PsiScanner.kt
│   │   ├── TypeResolver.kt
│   │   ├── ParamGenerator.kt
│   │   └── AnnotationParser.kt
│   │
│   ├── invoke/                      # 动态执行模块
│   │   ├── JvmAttachAgent.kt
│   │   ├── AgentConnection.kt
│   │   ├── SpringCtxFetcher.kt
│   │   └── BeanInvoker.kt
│   │
│   ├── http/                        # HTTP 测试模块
│   │   ├── RouteScanner.kt
│   │   └── HttpTestService.kt
│   │
│   └── infra/                       # 基础设施
│       ├── MethodIndexCache.kt
│       ├── StorageService.kt
│       └── JsonUtils.kt
│
├── src/main/resources/
│   ├── META-INF/plugin.xml          # 插件描述文件
│   └── agent/
│       └── inspector-agent.jar      # 预编译的 Agent Jar（打包在插件内）
│
├── agent/                           # Agent 独立模块（Java，零依赖）
│   └── src/main/java/
│       ├── InspectorAgent.java
│       ├── AgentServer.java
│       ├── SpringContextResolver.java
│       └── MethodInvoker.java
│
└── build.gradle.kts
```

---

## 五、plugin.xml 核心配置

```xml
<idea-plugin>
    <id>com.yourname.spring-bean-inspector</id>
    <name>JBeans</name>
    <vendor>YourName</vendor>
    
    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>
    
    <extensions defaultExtensionNs="com.intellij">
        <!-- 注册 Tool Window -->
        <toolWindow id="JBeans"
                    anchor="right"
                    factoryClass="...InspectorToolWindowFactory"
                    icon="/icons/plugin_icon.svg"/>
        
        <!-- 注册项目级服务 -->
        <projectService serviceImplementation="...MethodSearchService"/>
        <projectService serviceImplementation="...ProcessManager"/>
        
        <!-- PSI 变更监听 -->
        <psi.treeChangeListener implementation="...PsiChangeListener"/>
    </extensions>
</idea-plugin>
```
