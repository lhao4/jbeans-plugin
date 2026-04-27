## 📁 重命名后项目整体结构
```text
jbeans-agent
├── build/                # 构建产物目录（编译后的class、Agent包等）
├── src/
│   ├── main/
│   │   ├── java/         # Agent 核心逻辑实现
│   └── test/             # 单元测试目录（未展开）
└── build.gradle.kts      # Gradle 构建配置文件（Kotlin DSL）
```

---

## 🔍 重命名后核心模块解析

### 1. `src/main/java/com.jbeans.debug.agent`（Agent 核心逻辑）
这是一个 Java Agent 项目，用于挂载到运行中的 JVM 进程，为 IDEA 插件提供远程调用、服务查询等底层能力。

| 包名/文件 | 作用说明 |
|----------|----------|
| `handler/BeanLocator` | 负责从 Spring 上下文等容器中定位目标 Bean 实例，是服务调用的基础 |
| `handler/BeanResolveHandler` | 处理 Bean 解析请求，返回目标 Bean 的元信息（类名、方法列表等） |
| `handler/DiagHandler` | 诊断处理器，提供服务运行状态、环境信息等诊断数据 |
| `handler/HandlerUtil` | 处理器工具类，封装通用逻辑（如参数校验、请求分发等） |
| `handler/HealthHandler` | 健康检查处理器，响应健康检查请求，反馈 Agent 和目标服务的运行状态 |
| `handler/InvokeHandler` | 核心调用处理器，接收远程调用请求，执行目标服务方法并返回结果 |
| `handler/SchemaHandler` | 元信息处理器，返回服务接口的 Schema 定义（方法签名、参数类型等） |
| `handler/SchemaUtil` | Schema 工具类，负责接口元信息的序列化/反序列化、类型转换 |
| `handler/ServicesHandler` | 服务列表处理器，返回当前进程中所有已注册的服务信息 |
| `AgentLogger` | Agent 统一日志工具类，封装日志输出逻辑，方便调试和问题排查 |
| `JbeansDebugAgent` | Agent 入口类，`premain`/`agentmain` 方法的实现，负责 Agent 的初始化和启动 |
| `EmbeddedHttpServer` | 内嵌 HTTP 服务器，作为 Agent 与 IDEA 插件通信的桥梁，接收插件发来的请求 |
| `JsonUtil` | JSON 序列化/反序列化工具类，处理请求和响应数据的格式转换 |
| `ManualAttachTool` | 手动挂载工具，支持命令行方式将 Agent 挂载到目标 JVM 进程，方便测试 |
| `SpringContextLocator` | Spring 上下文定位器，用于获取 Spring 容器上下文，从而定位 Bean 和服务 |

---

## 💡 重命名后 Agent 整体工作流程
1.  Agent 通过 `JbeansDebugAgent` 的 `agentmain` 方法挂载到目标 JVM 进程。
2.  启动 `EmbeddedHttpServer`，监听本地端口，等待 IDEA 插件的 HTTP 请求。
3.  收到插件请求后，根据路径分发到对应的 `handler`：
    - `ServicesHandler` 返回所有服务列表
    - `SchemaHandler` 返回接口元信息
    - `InvokeHandler` 执行目标服务方法并返回结果
4.  `SpringContextLocator` 和 `BeanLocator` 配合，从容器中获取目标 Bean 实例，供 `InvokeHandler` 调用。
5.  所有请求/响应数据通过 `JsonUtil` 进行 JSON 序列化，`AgentLogger` 记录关键日志。

---

## 🎯 插件与 Agent 的协同关系
- **jbeans-plugin（IDEA 插件）**：用户交互层，提供 UI 界面、参数编辑、结果展示，向 Agent 发起 HTTP 请求。
- **jbeans-agent（Java Agent）**：底层执行层，挂载到目标服务进程，处理插件的请求，执行实际的服务调用和元信息查询。