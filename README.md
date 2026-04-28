# JBeans

> 在 IDEA 里直接连接本地运行中的 Java/Spring 进程，调用 Bean 方法做调试验证。

## 简介

`JBeans` 是一个 IntelliJ IDEA 插件，面向本地 Java 后端调试场景。它通过 JVM Attach API 将一个轻量 Agent 动态注入到目标进程，在不修改业务代码、不额外写测试入口的前提下，直接从 IDEA 中调用目标应用里的方法。

当前项目已经实现的核心能力包括：

- 扫描并连接本机 Java 进程
- 自动发现 Dubbo 暴露服务接口及其方法
- 手动指定任意 Spring Bean 方法进行调用
- 为方法参数生成默认 JSON 模板
- 在 IDEA 工具窗口内展示调用结果、异常和耗时

它适合用于以下场景：

- 本地验证某个 Service / Manager / Facade 方法
- 快速调试 Dubbo 服务实现而不额外写消费者
- 对复杂 DTO 入参做一次性调用验证
- 在断点调试之外，补充一种更轻量的“直接触发”方式

## 工作原理

JBeans 的调用链路如下：

```text
IDEA 插件
  -> JVM Attach API
  -> 注入 jbeans-agent
  -> 连接目标进程中的嵌入式 HTTP Server
  -> 获取 Spring ApplicationContext
  -> 定位 Bean 和方法
  -> JSON 参数反序列化
  -> 反射执行方法
  -> 返回结果给 IDEA
```

几个关键点：

- Agent 由插件在运行时动态注入，不需要你提前在应用启动参数里加 `-javaagent`
- Agent 监听在 `127.0.0.1:12138`
- 方法调用请求通过本地 HTTP 通信完成
- 目标应用退出后，Agent 也随进程结束，不会额外留下业务侧改动

## 当前功能

### 1. 进程连接

- 扫描本机 Java 进程，过滤掉 IDEA、自身 Gradle 等常见工具进程
- 在工具栏中选择目标 PID 并建立连接
- 连接成功后自动做健康检查和心跳检测
- 目标进程不可达时自动断开并刷新状态

### 2. 服务接口发现

- 自动扫描目标 Spring 容器中的 Dubbo 服务
- 支持识别常见 `@DubboService` / `@Service` 暴露方式
- 左侧树中展示接口名和方法签名
- 支持按接口名、方法名做快速过滤

### 3. 任意 Bean 方法加载

- 支持手动输入 `类全限定名#方法名`
- 插件会到目标 Spring 容器中定位对应 Bean
- 自动解析目标方法参数、返回值类型和参数默认结构

示例：

```text
com.example.order.OrderService#createOrder
```

### 4. 参数模板生成

参数编辑区会尽量自动生成默认 JSON：

- 优先使用目标 JVM 运行时反射出的参数结构
- 不足时回退到 IDEA PSI 源码分析
- 再不行则使用基础类型兜底默认值

支持的常见类型：

- `String`、数值、布尔值
- `List`、`Set`、`Map`
- 自定义 DTO / POJO
- 数组、枚举、日期时间类

### 5. 调用结果展示

- 显示格式化后的 JSON 结果
- 展示执行耗时
- 失败时展示错误码、异常消息和堆栈
- 支持一键复制结果和清空结果区

## 使用要求

### IDEA 版本

- IntelliJ IDEA 2022.3 及以上

当前插件配置的兼容范围来自 `gradle.properties`：

- `since-build=223`
- `until-build=253.*`

### 目标应用要求

- 目标进程必须是本机运行中的 Java 进程
- 目标应用需要已经完成 Spring 容器初始化
- 更适合 Spring Boot / Spring 应用场景
- 若要使用“服务接口发现”，目标应用需要存在 Dubbo 暴露服务

### Java 版本

- 插件构建使用 JDK 17
- 动态注入的 Agent 兼容 Java 8 及以上目标 JVM

## 安装方式

### 1. 从 GitHub Releases 安装

进入仓库的 `Releases` 页面下载插件 ZIP，然后在 IDEA 中执行：

`Settings` -> `Plugins` -> `Install Plugin from Disk`

### 2. 从本地构建产物安装

先执行构建：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :jbeans-plugin:buildPlugin
```

生成的插件包位于：

```text
jbeans-plugin/build/distributions/*.zip
```

然后在 IDEA 中选择该 ZIP 安装。

## 使用说明

### 1. 打开工具窗口

在 IDEA 中打开：

`View` -> `Tool Windows` -> `JBeans`

### 2. 连接目标进程

- 点击顶部刷新按钮扫描本机 Java 进程
- 在下拉框中选择目标进程
- 点击 `连接`
- 连接成功后状态会显示为已连接

### 3. 选择调用方式

有两种方式：

- 在左侧服务树中选择自动发现的服务方法
- 在“指定方法”输入框中手动输入 `类全限定名#方法名` 后点击 `加载`

### 4. 编辑参数

- 中间编辑区会自动生成 JSON 参数模板
- 你可以直接修改字段值
- 如果 JSON 不合法，调用前会先提示格式错误

### 5. 执行调用

- 点击 `测试`
- 底部结果区会显示调用结果
- 如果业务方法抛异常，也会显示错误信息和堆栈

## 已知边界

下面这些是当前代码层面的真实限制，README 明确写出来比含糊其辞更有用：

- 当前 UI 不是多标签页模式，主要聚焦“服务方法 / Bean 方法调用”
- README 旧版本里提到的 HTTP 测试功能，当前仓库代码里并没有对应 UI 能力
- 手动加载 Bean 方法时，如果存在方法重载，当前默认使用返回结果中的第一个重载
- 调用依赖目标应用里的 Spring `ApplicationContext` 已经可用
- 插件当前默认通过 `127.0.0.1:12138` 与 Agent 通信
- 仅支持本机进程调试，不面向远程服务

## 项目结构

```text
jbeans-plugin/
  jbeans-plugin/   IDEA 插件模块，负责 UI、进程连接、参数编辑、结果展示
  jbeans-agent/    动态注入到目标 JVM 的 Agent，负责 Bean 定位、方法调用、Schema 生成
  docs/            产品与架构文档
  .github/         GitHub Actions 构建与发布流程
```

## 开发与构建

### 本地开发

建议使用：

- JDK 17
- IntelliJ IDEA 2022.3+

常用命令：

```bash
./gradlew :jbeans-plugin:buildPlugin
./gradlew verifyPluginProjectConfiguration
./gradlew verifyPlugin
```

说明：

- `buildPlugin` 生成可安装的 IDEA 插件 ZIP
- `verifyPluginProjectConfiguration` 校验插件工程配置
- `verifyPlugin` 使用 JetBrains Plugin Verifier 做兼容性检查

## GitHub 自动构建与发布

仓库已经配置了两条 GitHub Actions 流程：

- [`.github/workflows/build.yml`](.github/workflows/build.yml)
  每次推送到 `main` 或发起 PR 时构建插件，并上传 ZIP artifact
- [`.github/workflows/release.yml`](.github/workflows/release.yml)
  每次推送形如 `v0.0.2` 的 tag 时，自动构建插件并发布到 GitHub Releases

发布示例：

```bash
git tag v0.0.2
git push origin v0.0.2
```

如果你希望代码中的版本号与 release 对齐，记得同步更新 `gradle.properties` 里的 `pluginVersion`。

## 后续建议

如果你准备继续把这个项目做成可长期分发的 IDEA 插件，下一步更值得做的是：

- 补齐 HTTP 接口测试 UI，再写回 README
- 处理 Bean 方法重载选择，而不是默认取第一个
- 增加调用历史记录与收藏
- 增加 JetBrains Marketplace 发布流程

## License

[MIT](LICENSE)
