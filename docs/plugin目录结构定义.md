## 📁 plugin项目整体结构
```text
jbeans-plugin
├── build/                # 构建产物目录（编译后的class、插件包等）
├── src/
│   ├── main/
│   │   ├── java/         # Java 实现的核心逻辑
│   │   ├── kotlin/       # Kotlin 实现的服务与UI层
│   │   └── resources/    # 插件资源文件
└── build.gradle.kts      # Gradle 构建配置文件（Kotlin DSL）
```

---

## 🔍 重命名后核心模块解析

### 1. `src/main/java/com.jbeans.debug.plugin`（Java 核心逻辑）
底层核心能力实现，Java 编写

| 包名/文件 | 作用说明 |
|----------|----------|
| `attach/AgentAttachManager` | 负责将 Java Agent 挂载到目标 JVM 进程，实现对运行中服务的动态调试 |
| `attach/ProcessDiscovery` | 扫描本地运行的 Java 进程，筛选出正在运行的目标服务进程，供用户选择连接 |
| `client/JbeansDebugClient` | 客户端核心，封装了与服务的通信逻辑，包括服务发现、调用、序列化等 |
| `client/JbeansDebugException` | 自定义异常类，统一处理服务调用过程中的各类错误（连接失败、超时、序列化异常等） |
| `param/DefaultValueGenerator` | 为接口参数自动生成默认值，方便用户快速发起测试调用，减少手动填参成本 |

---

### 2. `src/main/kotlin/com.jbeans.debug.plugin`（Kotlin 服务与 UI 层）
插件业务逻辑 + IDE 界面实现，Kotlin 编写

| 包名/文件 | 作用说明 |
|----------|----------|
| `service/JbeansProjectService.kt` | 插件业务服务层，处理项目级别逻辑：解析接口定义、维护连接状态、管理调用记录等 |
| `ui/ConnectionPanel` | 连接配置面板，用户配置目标服务地址、注册中心、协议等连接信息 |
| `ui/JbeansToolWindowFactory` | IDE 工具窗口工厂类，创建并注册插件主窗口，显示在 IDE 侧边栏 |
| `ui/InterfaceTreePanel` | 接口树形面板，展示项目/已连接服务下的所有接口和方法，供选择调试 |
| `ui/ParameterEditorPanel` | 参数编辑面板，可视化编辑接口调用参数，支持复杂对象编辑 |
| `ui/ResultPanel` | 调用结果展示面板，显示返回值、耗时、异常信息，支持格式化展示 |

---

### 3. `src/main/resources`（插件资源文件）
插件运行所需非代码资源

| 目录/文件 | 作用说明 |
|----------|----------|
| `icons/` | 存放插件图标资源，`jbeans-tester.svg` 为插件主图标 |
| `META-INF/plugin.xml` | IDEA 插件核心配置文件：定义插件名称、版本、依赖、扩展点（工具窗口/菜单） |
| `META-INF/pluginIcon.svg` | 插件在 IDEA 市场/设置界面显示的图标 |

---

## 💡 重命名后插件整体工作流程
1. 用户在 IDEA 打开项目，插件通过 `JbeansProjectService` 解析项目接口
2. 在 `ConnectionPanel` 配置并连接目标服务（扫描本地进程/手动填写地址）
3. 通过 `AgentAttachManager` 挂载调试 Agent，或直接用 `JbeansDebugClient` 发起远程调用
4. 在 `InterfaceTreePanel` 选择接口，`ParameterEditorPanel` 编辑参数（自动生成默认值）
5. 发起调用后，在 `ResultPanel` 展示结果