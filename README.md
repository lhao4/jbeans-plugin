# JBeans

> **Invoke any Spring Bean method, without writing a single line of test code.**

**JBeans** is an IntelliJ IDEA plugin that lets you invoke any Spring Bean method — including Dubbo services, Spring Services, DAOs, and static utility methods — directly from your IDE, with zero code changes and zero configuration.

Think of it as **Postman for your Spring internals**.

## How It Works

JBeans uses the **JVM Attach API** to connect to your running Spring process. It injects a lightweight in-memory agent, retrieves the `ApplicationContext`, and invokes the target Bean method via reflection. No network, no RPC, no registry.

```
Your IDE  ──(JVM Attach)──▶  Running Spring Process
                               └─▶ ApplicationContext
                                     └─▶ YourService.yourMethod(args)
                                           └─▶ Result returned to IDE
```

The agent lives only in memory and leaves no trace after the process exits.
