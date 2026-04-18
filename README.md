# JBeans

> **Invoke any Spring Bean method, without writing a single line of test code.**

**JBeans** is an IntelliJ IDEA plugin that lets you invoke Spring Bean methods, static utility methods, and HTTP endpoints directly from your IDE — with zero code changes and zero configuration.

Think of it as **Postman for your Spring internals**.

## How It Works

JBeans uses the **JVM Attach API** to connect to your running Spring process. It injects a lightweight in-memory agent, retrieves the `ApplicationContext`, and invokes the target method via reflection.

```
Your IDE  ──(JVM Attach)──▶  Running Spring Process
                               └─▶ ApplicationContext
                                     └─▶ YourService.yourMethod(args)
                                           └─▶ Result returned to IDE
```

The agent lives only in memory and leaves no trace after the process exits.

## Requirements

- IntelliJ IDEA 2023.2 or later
- Java 8+ target JVM
- Spring Boot application running locally

## Installation

Install from the [JetBrains Marketplace](https://plugins.jetbrains.com/) by searching for **JBeans**, or install manually via **Settings → Plugins → Install Plugin from Disk**.

## Usage

Open the **JBeans** tool window from the right-side panel (or via **View → Tool Windows → JBeans**).

### 1. Connect to a Process

At the top of the tool window, select the target JVM process from the dropdown and click **连接** (Connect).

- The dropdown lists all running JVM processes on your machine with their main class and PID.
- The status indicator turns green when connected.
- Click **断开** (Disconnect) to detach at any time.
- If the process exits, the status resets automatically.

### 2. Beans Tab — Invoke Spring Bean Methods

Use this tab to call any method on a Spring-managed Bean.

1. **Search** for a Bean by class name, method name, or annotation (`@Service`, `@Component`, etc.) in the left search panel.
2. **Select** a method from the results list. The right panel loads with the method signature and a pre-filled JSON parameter editor.
3. **Edit** the JSON if needed. Invalid JSON is highlighted in red.
4. Click **Invoke**. The result (serialized as JSON) appears in the result panel below with execution time.
5. Click **Cancel** to abort a long-running invocation.

**Call History** is shown below the search panel. Double-click any entry to replay it. Right-click to star/unstar.

### 3. Static Tab — Invoke Static Methods

Use this tab to call any `public static` method on any class in your project — useful for utility classes, converters, formatters, etc.

The workflow is identical to the Beans tab: search → select → edit JSON → Invoke.

> Note: Static methods are invoked reflectively inside the target JVM, so they run with the same classpath and environment as your running application.

### 4. HTTP Tab — Test HTTP Endpoints

Use this tab to send HTTP requests to your Spring MVC / Spring Boot endpoints.

1. **Search** for a route by path or controller class name in the left panel. Routes are color-coded by method (GET / POST / PUT / DELETE / PATCH).
2. **Select** a route. The right panel populates:
   - **URL bar** — auto-filled with `http://localhost:{server.port}{path}`.
   - **Path variables** — one input field per `{variable}` in the path.
   - **Query parameters** — one row per `@RequestParam`.
   - **Body editor** — pre-filled with a JSON template derived from `@RequestBody` type if present.
3. Edit values as needed, then click **Send**. The raw HTTP response (status + body) is shown below.
4. Click **Cancel** to abort an in-flight request.

> The port is read from `server.port` in the connected process. If not available, it defaults to `8080`.

## License

[MIT](LICENSE)
