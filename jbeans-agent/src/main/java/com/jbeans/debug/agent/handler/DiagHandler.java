package com.jbeans.debug.agent.handler;

import java.lang.instrument.Instrumentation;

import com.jbeans.debug.agent.SpringContextLocator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.management.ManagementFactory;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public class DiagHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("=== JBeans Debug Agent Diagnostics ===\n\n");

        ClassLoader appCl = SpringContextLocator.findAppClassLoader();
        Instrumentation inst = SpringContextLocator.getInstrumentation();

        // [1] Thread ClassLoaders
        sb.append("[1] Thread ClassLoaders:\n");
        LinkedHashMap<String, Integer> clCounts = new LinkedHashMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = t.getContextClassLoader();
            String name = cl != null ? cl.getClass().getName() : "null";
            Integer count = clCounts.get(name);
            clCounts.put(name, count == null ? 1 : count + 1);
        }
        for (Map.Entry<String, Integer> e : clCounts.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n");
        }

        // [2] AppClassLoader
        sb.append("\n[2] findAppClassLoader: ").append(appCl.getClass().getName()).append("\n");

        // [3] Spring Classes
        sb.append("\n[3] Spring Classes:\n");
        String[] springClasses = {
                "org.springframework.context.ApplicationContext",
                "org.springframework.context.support.AbstractApplicationContext",
                "org.springframework.boot.SpringApplication",
                "org.springframework.boot.admin.SpringApplicationAdminMXBeanRegistrar",
                "org.springframework.web.context.ContextLoader",
                "org.springframework.context.support.LiveBeansView",
        };
        for (String cls : springClasses) {
            try {
                Class.forName(cls, false, appCl);
                sb.append("  OK ").append(cls).append("\n");
            } catch (ClassNotFoundException ex) {
                sb.append("  !! ").append(cls).append("\n");
            }
        }

        // [4] Spring MBeans
        sb.append("\n[4] Spring MBeans:\n");
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName adminName = new ObjectName(
                    "org.springframework.boot:type=Admin,name=SpringApplication");
            sb.append("  Admin MBean registered: ").append(mbs.isRegistered(adminName)).append("\n");
            for (ObjectName name : mbs.queryNames(null, null)) {
                if (name.getDomain().contains("spring") || name.getDomain().contains("Spring")) {
                    sb.append("  ").append(name).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("  Error: ").append(e.getMessage()).append("\n");
        }

        // [5] Strategy Results
        sb.append("\n[5] Strategy Results:\n");
        appendStrategy(sb, "MBeanServer deep reflection",
                SpringContextLocator.tryMBeanServerDeepReflection(appCl));
        appendStrategy(sb, "Static field scan",
                SpringContextLocator.tryStaticFieldScan(appCl));
        appendStrategy(sb, "ShutdownHook reflection",
                SpringContextLocator.tryShutdownHookReflection(appCl));
        appendStrategy(sb, "ContextLoader",
                SpringContextLocator.tryContextLoader(appCl));
        appendStrategy(sb, "LiveBeansView",
                SpringContextLocator.tryLiveBeansView(appCl));

        // [6] Instrumentation
        sb.append("\n[6] Instrumentation:\n");
        sb.append("  Available: ").append(inst != null).append("\n");
        if (inst != null) {
            int count = 0;
            try {
                Class<?> acClass = Class.forName(
                        "org.springframework.context.ApplicationContext", false, appCl);
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (acClass.isAssignableFrom(c)) {
                        count++;
                        sb.append("  AC impl: ").append(c.getName()).append("\n");
                    }
                }
            } catch (Exception e) {
                sb.append("  Error scanning: ").append(e.getMessage()).append("\n");
            }
            sb.append("  ApplicationContext implementations found: ").append(count).append("\n");
        }

        // [7] MBean Deep Reflection Debug
        sb.append("\n[7] MBean Deep Reflection Debug:\n");
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            sb.append("  MBS class: ").append(mbs.getClass().getName()).append("\n");
            sb.append("  MBS fields:\n");
            for (Field f : mbs.getClass().getDeclaredFields()) {
                sb.append("    ").append(f.getType().getSimpleName()).append(" ")
                        .append(f.getName()).append("\n");
            }

            Object interceptor = SpringContextLocator.getFieldValueRecursive(mbs, "mbsInterceptor");
            sb.append("  mbsInterceptor: ").append(interceptor != null
                    ? interceptor.getClass().getName() : "null").append("\n");

            if (interceptor != null) {
                Object repository = SpringContextLocator.getFieldValueRecursive(
                        interceptor, "repository");
                sb.append("  repository: ").append(repository != null
                        ? repository.getClass().getName() : "null").append("\n");

                if (repository != null) {
                    ObjectName adminName = new ObjectName(
                            "org.springframework.boot:type=Admin,name=SpringApplication");
                    try {
                        Object retrieved = null;
                        for (Method m : repository.getClass().getDeclaredMethods()) {
                            if ("retrieve".equals(m.getName())
                                    && m.getParameterTypes().length == 1
                                    && m.getParameterTypes()[0] == ObjectName.class) {
                                m.setAccessible(true);
                                retrieved = m.invoke(repository, adminName);
                                break;
                            }
                        }
                        sb.append("  retrieved: ").append(retrieved != null
                                ? retrieved.getClass().getName() : "null").append("\n");

                        if (retrieved != null) {
                            Object unwrapped = SpringContextLocator.unwrapMBean(retrieved);
                            sb.append("  unwrapped: ").append(unwrapped.getClass().getName())
                                    .append("\n");
                        }
                    } catch (Exception ex) {
                        sb.append("  retrieve error: ").append(ex.getClass().getSimpleName())
                                .append(" - ").append(ex.getMessage()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("  Error: ").append(e.getClass().getSimpleName())
                    .append(" - ").append(e.getMessage()).append("\n");
        }

        // [8] ShutdownHook Debug
        sb.append("\n[8] ShutdownHook Debug:\n");
        try {
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            IdentityHashMap<Thread, Thread> hooks =
                    (IdentityHashMap<Thread, Thread>) hooksField.get(null);
            sb.append("  hooks count: ").append(hooks != null ? hooks.size() : "null").append("\n");
            if (hooks != null) {
                for (Thread t : hooks.keySet()) {
                    sb.append("  hook: ").append(t.getClass().getName())
                            .append(" name=").append(t.getName()).append("\n");
                    // Try to get Runnable from holder.task (JDK 19+)
                    try {
                        Field holderField = Thread.class.getDeclaredField("holder");
                        holderField.setAccessible(true);
                        Object holder = holderField.get(t);
                        if (holder != null) {
                            Field taskField = holder.getClass().getDeclaredField("task");
                            taskField.setAccessible(true);
                            Object task = taskField.get(holder);
                            sb.append("    holder.task: ").append(task != null
                                    ? task.getClass().getName() : "null").append("\n");
                        }
                    } catch (Exception ex) {
                        sb.append("    holder.task error: ").append(ex.getClass().getSimpleName())
                                .append(" - ").append(ex.getMessage()).append("\n");
                    }

                    // Also try Thread.target (JDK 8-18)
                    try {
                        Field targetField = Thread.class.getDeclaredField("target");
                        targetField.setAccessible(true);
                        Object target = targetField.get(t);
                        sb.append("    target: ").append(target != null
                                ? target.getClass().getName() : "null").append("\n");
                    } catch (NoSuchFieldException nsfe) {
                        sb.append("    target: NoSuchFieldException (JDK 19+)\n");
                    } catch (Exception ex) {
                        sb.append("    target error: ").append(ex.getMessage()).append("\n");
                    }
                }
            }
        } catch (Exception ex) {
            sb.append("  holder.task error: ").append(ex.getClass().getSimpleName())
                    .append(" - ").append(ex.getMessage()).append("\n");
        }

        // [9] SpringApplicationShutdownHook class search
        sb.append("\n[9] ShutdownHook Class Search:\n");
        if (inst != null) {
            boolean found = false;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().contains("ShutdownHook")) {
                    sb.append("  class: ").append(c.getName())
                            .append(" CL=").append(c.getClassLoader()).append("\n");
                    if (c.getName().equals("org.springframework.boot.SpringApplicationShutdownHook")) {
                        found = true;
                        for (Field f : c.getDeclaredFields()) {
                            sb.append("    field: ").append(Modifier.isStatic(f.getModifiers()) ? "static " : "")
                                    .append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
                        }
                    }
                }
            }
            if (!found) {
                sb.append("  SpringApplicationShutdownHook class NOT FOUND\n");
            }
        }

        HandlerUtil.sendText(exchange, 200, sb.toString());
    }

    private void appendStrategy(StringBuilder sb, String name, Object result) {
        sb.append("  ").append(name).append(": ")
                .append(result != null ? result.getClass().getName() : "null").append("\n");
    }
}
