package com.jbeans.debug.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jbeans.debug.agent.AgentLogger.log;

/**
 * JBeans Debug Agent V2 入口。
 * <p>
 * 由 {@code VirtualMachine.loadAgent()} 触发 {@code agentmain()}。
 * 实际逻辑委托给 {@link SpringContextLocator}、{@link EmbeddedHttpServer}，
 * 以及 {@code handler/*} 中的 HTTP Handler。
 */
public class JbeansDebugAgent {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    public static final int PORT = 12138;

    /**
     * 动态 Attach 入口。
     *
     * @param agentArgs 格式: "port=12138"
     * @param inst      Instrumentation 实例
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        int port = parsePort(agentArgs);
        log("agentmain called, args=" + agentArgs
                + ", firstTime=" + !INITIALIZED.get() + ", port=" + port);

        if (!INITIALIZED.compareAndSet(false, true)) {
            log("Already initialized. Restarting HTTP Server on port " + port);
            EmbeddedHttpServer.restart(port);
            return;
        }

        // JDK 9+ 模块系统: 打开必要的模块以允许反射访问内部 API
        openJdkModules(inst);

        EmbeddedHttpServer.start(port);
        SpringContextLocator.startPolling(inst);
    }

    private static int parsePort(String agentArgs) {
        if (agentArgs != null && !agentArgs.isEmpty()) {
            for (String part : agentArgs.split("&")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("port=")) {
                    try {
                        return Integer.parseInt(trimmed.substring(5));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return PORT;
    }

    // ==================== JDK 模块系统打开 ====================

    /**
     * 通过反射调用 {@code Instrumentation.redefineModule()} 打开 JDK 内部包。
     * 不宜直接引用 {@code Module} 类，确保 Java 8 编译兼容。
     * <p>
     * 打开的包:
     * <ul>
     * <li>{@code java.base/java.lang} - ShutdownHook 反射</li>
     * <li>{@code java.management/com.sun.jmx.mbeanserver} - MBeanServer 深度反射</li>
     * <li>{@code java.management/com.sun.jmx.interceptor} - MBeanServer 拦截器</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static void openJdkModules(Instrumentation inst) {
        try {
            Class.forName("java.lang.Module"); // JDK 9+ 检测

            Method redefineModule = null;
            for (Method m : Instrumentation.class.getMethods()) {
                if ("redefineModule".equals(m.getName())) {
                    redefineModule = m;
                    break;
                }
            }
            if (redefineModule == null) {
                log("redefineModule not available – skipping module opens");
                return;
            }

            Method getModule = Class.class.getMethod("getModule");
            Object unnamedModule = getModule.invoke(JbeansDebugAgent.class);

            Set<Object> targetModules = new HashSet<>();
            targetModules.add(unnamedModule);
            Set<Object> emptySet = Collections.emptySet();
            Map<Object, Object> emptyMap = Collections.emptyMap();

            // 打开 java.base/java.lang
            Object javaBase = getModule.invoke(Object.class);
            Map<String, Set<Object>> baseOpens = new HashMap<>();
            baseOpens.put("java.lang", targetModules);
            redefineModule.invoke(inst, javaBase, emptySet, emptyMap,
                    baseOpens, emptySet, emptyMap);
            log("Opened java.base/java.lang to agent");

            // 打开 java.management 内部包
            Object javaMgmt = getModule.invoke(Class.forName("com.sun.jmx.mbeanserver.MBeanServer"));
            Map<String, Set<Object>> mgmtOpens = new HashMap<>();
            mgmtOpens.put("com.sun.jmx.mbeanserver", targetModules);
            mgmtOpens.put("com.sun.jmx.interceptor", targetModules);
            redefineModule.invoke(inst, javaMgmt, emptySet, emptyMap,
                    mgmtOpens, emptySet, emptyMap);
            log("Opened java.management internal packages to agent");

        } catch (ClassNotFoundException e) {
            log("Module class not found (JDK 8) – skipping module opens");
        } catch (NoSuchMethodException e) {
            log("getModule not available – skipping module opens");
        } catch (Exception e) {
            log("WARNING: Failed to open JDK modules: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        }
    }

}
