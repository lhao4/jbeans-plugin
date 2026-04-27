package com.jbeans.debug.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.*;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import static com.jbeans.debug.agent.AgentLogger.log;

/**
 * 多策略获取 Spring ApplicationContext。
 * <p>
 * 所有 Spring/服务框架交互均通过反射完成，不 import 任何框架类。
 * <p>
 * 策略优先级：
 * <ol>
 *     <li>MBeanServer 深度反射（IDEA Run/Debug 模式最可靠）</li>
 *     <li>静态字段扫描（Instrumentation.getAllLoadedClasses()）</li>
 *     <li>ShutdownHook 线程反射（Spring Boot 2.5+）</li>
 *     <li>ContextLoader.getCurrentWebApplicationContext（ThreadLocal，Agent 线程通常无效）</li>
 *     <li>LiveBeansView（默认关闭，作为 fallback）</li>
 * </ol>
 */
public final class SpringContextLocator {

    private static volatile Object springContext;
    private static volatile Instrumentation globalInst;

    private SpringContextLocator() {
    }

    public static Object getContext() {
        if (springContext != null && isContextActive(springContext)) {
            return springContext;
        }
        try {
            springContext = findSpringContext();
        } catch (Throwable t) {
            log("Context lookup failed: " + t.getClass().getSimpleName()
                    + " - " + t.getMessage());
            springContext = null;
        }
        return springContext;
    }

    public static Instrumentation getInstrumentation() {
        return globalInst;
    }

    /**
     * 在后台守护线程中轮询获取 ApplicationContext（500ms 间隔，60s 超时）。
     */
    public static void startPolling(Instrumentation inst) {
        globalInst = inst;
        Thread poller = new Thread(new Runnable() {
            @Override
            public void run() {
                log("Starting ApplicationContext polling (timeout=60s)...");
                long deadline = System.currentTimeMillis() + 60_000;
                while (System.currentTimeMillis() < deadline) {
                    Object ctx;
                    try {
                        ctx = findSpringContext();
                    } catch (Throwable t) {
                        log("Polling context lookup failed: " + t.getClass().getSimpleName()
                                + " - " + t.getMessage());
                        ctx = null;
                    }
                    if (ctx != null) {
                        springContext = ctx;
                        log("ApplicationContext acquired: " + ctx.getClass().getName());
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                log("WARNING: ApplicationContext polling timed out after 60s");
            }
        }, "jbeans-debug-agent-poller");
        poller.setDaemon(true);
        poller.start();
    }

    // ================ 5 策略入口 ================
    static Object findSpringContext() {
        ClassLoader appCl = findAppClassLoader();

        Object ctx = tryMBeanServerDeepReflection(appCl);
        if (ctx != null) {
            log("Context via MBeanServer deep reflection");
            return ctx;
        }

        ctx = tryStaticFieldScan(appCl);
        if (ctx != null) {
            log("Context via static field scan");
            return ctx;
        }

        ctx = tryShutdownHookReflection(appCl);
        if (ctx != null) {
            log("Context via ShutdownHook");
            return ctx;
        }

        ctx = tryContextLoader(appCl);
        if (ctx != null) {
            log("Context via ContextLoader");
            return ctx;
        }

        ctx = tryLiveBeansView(appCl);
        if (ctx != null) {
            log("Context via LiveBeansView");
            return ctx;
        }

        return null;
    }

    // ================ ClassLoader 查找 ================
    public static ClassLoader findAppClassLoader() {
        // 优先查找 Spring Boot fat jar 的 ClassLoader
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = t.getContextClassLoader();
            if (cl == null) continue;
            String clName = cl.getClass().getName();
            if (clName.contains("LaunchedURLClassLoader")
                    || clName.contains("LaunchedClassLoader")) {
                return cl;
            }
        }

        // Fallback: 找能加载 Spring 的 ClassLoader（IDEA Run 模式）
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = t.getContextClassLoader();
            if (cl == null) continue;
            try {
                Class.forName("org.springframework.context.ApplicationContext", false, cl);
                return cl;
            } catch (ClassNotFoundException ignored) {
            }
        }

        log("WARNING: No Spring-capable ClassLoader found, using System CL");
        return ClassLoader.getSystemClassLoader();
    }

    // ================ 策略 1: MBeanServer 深度反射 ================
    public static Object tryMBeanServerDeepReflection(ClassLoader appCl) {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName adminName = new ObjectName(
                    "org.springframework.boot:type=Admin,name=SpringApplication");
            if (!mbs.isRegistered(adminName)) return null;

            Object interceptor = getFieldValueRecursive(mbs, "mbsInterceptor");
            if (interceptor == null) {
                Object innerMbs = getFieldValueRecursive(mbs, "mbs");
                if (innerMbs != null) {
                    interceptor = getFieldValueRecursive(innerMbs, "mbsInterceptor");
                }
            }
            if (interceptor == null) return null;

            Object repository = getFieldValueRecursive(interceptor, "repository");
            if (repository == null) return null;

            // retrieve() 在不同 JDK 返回不同类型
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
            if (retrieved == null) return null;

            // 兼容 NamedObject（有 getObject）和 MXBeanSupport（无 getObject）
            Object dynamicMBean = retrieved;
            try {
                Method getObject = retrieved.getClass().getDeclaredMethod("getObject");
                getObject.setAccessible(true);
                Object inner = getObject.invoke(retrieved);
                if (inner != null) dynamicMBean = inner;
            } catch (NoSuchMethodException ignored) {
            }

            Object actualBean = unwrapMBean(dynamicMBean);

            Class<?> acClass = Class.forName(
                    "org.springframework.context.ApplicationContext", true, appCl);

            Object ctx = getFieldValueRecursive(actualBean, "applicationContext");
            if (ctx != null && acClass.isInstance(ctx) && isContextActive(ctx)) {
                return ctx;
            }

            // 遍历所有字段找 ApplicationContext 类型
            Class<?> beanClass = actualBean.getClass();
            while (beanClass != null && beanClass != Object.class) {
                for (Field f : safeGetDeclaredFields(beanClass)) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(actualBean);
                        if (val != null && acClass.isInstance(val) && isContextActive(val)) {
                            return val;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                beanClass = beanClass.getSuperclass();
            }
        } catch (Throwable e) {
            log("MBeanServer strategy failed: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        }
        return null;
    }

    // ================ 策略 2: 静态字段扫描 ================
    public static Object tryStaticFieldScan(ClassLoader appCl) {
        if (globalInst == null) return null;
        try {
            Class<?> acClass = Class.forName("org.springframework.context.ApplicationContext", false, appCl);
            for (Class<?> clazz : globalInst.getAllLoadedClasses()) {
                String name = clazz.getName();
                if (name.startsWith("java.") || name.startsWith("javax.")
                        || name.startsWith("jdk.") || name.startsWith("sun.")
                        || name.startsWith("com.sun.")) {
                    continue;
                }
                for (Field f : safeGetDeclaredFields(clazz)) {
                    try {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        if (!acClass.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val != null && isContextActive(val)) {
                            log("Found context in static field: " + name + "." + f.getName());
                            return val;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            log("Static field scan failed: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        }
        return null;
    }

    // ================ 策略 3: ShutdownHook 反射 ================
    @SuppressWarnings("unchecked")
    public static Object tryShutdownHookReflection(ClassLoader appCl) {
        try {
            Class<?> acClass = Class.forName("org.springframework.context.ConfigurableApplicationContext", true, appCl);

            // 路径 A: 通过 Instrumentation 直接找 SpringApplicationShutdownHook 类实例
            if (globalInst != null) {
                Object ctx = tryShutdownHookViaInstrumentation(acClass);
                if (ctx != null) return ctx;
            }

            // 路径 B: 经典方式 - 遍历 JDK ShutdownHooks map
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks", true, appCl);
            Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            Map<Thread, Thread> hooks = (Map<Thread, Thread>) hooksField.get(null);
            if (hooks == null) return null;

            for (Thread hookThread : hooks.keySet()) {
                // 获取 Thread 的 Runnable target
                // JDK 8-18: Thread.target 字段
                // JDK 19+: Thread.holder (FieldHolder) → task 字段
                Object target = null;
                try {
                    Field targetField = Thread.class.getDeclaredField("target");
                    targetField.setAccessible(true);
                    target = targetField.get(hookThread);
                } catch (NoSuchFieldException ignored) {
                    // JDK 19+: Thread$FieldHolder.task
                    try {
                        Field holderField = Thread.class.getDeclaredField("holder");
                        holderField.setAccessible(true);
                        Object holder = holderField.get(hookThread);
                        if (holder != null) {
                            Field taskField = holder.getClass().getDeclaredField("task");
                            taskField.setAccessible(true);
                            target = taskField.get(holder);
                        }
                    } catch (Exception e) {
                        log("ShutdownHook: holder.task access failed: " + e.getMessage());
                    }
                }

                if (target != null) {
                    if (acClass.isInstance(target)) return target;
                    Object ctx = findContextDeep(target, acClass);
                    if (ctx != null) {
                        log("Found context via ShutdownHook target (" + target.getClass().getName() + ")");
                        return ctx;
                    }
                }

                Object ctx = findContextDeep(hookThread, acClass);
                if (ctx != null) {
                    log("Found context in ShutdownHook thread fields");
                    return ctx;
                }
            }
        } catch (Exception e) {
            log("ShutdownHook strategy failed: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * 通过 Instrumentation.getAllLoadedClasses() 找 SpringApplicationShutdownHook 类，
     * 从其 INSTANCE 静态字段 → contexts 字段中获取 ApplicationContext。
     * Spring Boot 2.5+: SpringApplicationShutdownHook 有 INSTANCE 单例。
     */
    private static Object tryShutdownHookViaInstrumentation(Class<?> acClass) {
        for (Class<?> clazz : globalInst.getAllLoadedClasses()) {
            if (!"org.springframework.boot.SpringApplicationShutdownHook".equals(clazz.getName())) {
                continue;
            }
            log("ShutdownHook: Found class " + clazz.getName() + " in CL " + clazz.getClassLoader());
            // 尝试获取 INSTANCE 单例
            Object instance = null;
            try {
                Field instanceField = clazz.getDeclaredField("INSTANCE");
                instanceField.setAccessible(true);
                instance = instanceField.get(null);
            } catch (NoSuchFieldException e) {
                // 非 2.5+ 版本或字段名不同，尝试其他静态字段
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val != null && clazz.isInstance(val)) {
                                instance = val;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            if (instance == null) {
                log("ShutdownHook: No singleton instance found");
                continue;
            }

            log("ShutdownHook: Got instance of " + instance.getClass().getName());
            // 在实例中深度搜索 ApplicationContext
            Object ctx = findContextDeep(instance, acClass);
            if (ctx != null) {
                log("Found context via ShutdownHook INSTANCE deep search");
                return ctx;
            }

            // 也遍历所有字段名包含 context 的
            Class<?> instClass = instance.getClass();
            while (instClass != null && instClass != Object.class) {
                for (Field f : safeGetDeclaredFields(instClass)) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(instance);
                        if (val == null) continue;
                        log("ShutdownHook: field " + f.getName() + " type=" + val.getClass().getName());
                    } catch (Throwable ignored) {
                    }
                }
                instClass = instClass.getSuperclass();
            }
        }
        return null;
    }

    // ================ 策略 4: ContextLoader ================
    public static Object tryContextLoader(ClassLoader appCl) {
        try {
            Class<?> clazz = Class.forName("org.springframework.web.context.ContextLoader", true, appCl);
            Method method = clazz.getMethod("getCurrentWebApplicationContext");
            return method.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ================ 策略 5: LiveBeansView ================
    public static Object tryLiveBeansView(ClassLoader appCl) {
        try {
            Class<?> clazz = Class.forName("org.springframework.context.support.LiveBeansView", true, appCl);
            Field field = clazz.getDeclaredField("applicationContexts");
            field.setAccessible(true);
            Object set = field.get(null);
            if (set == null) return null;
            Method isEmpty = set.getClass().getMethod("isEmpty");
            if ((boolean) isEmpty.invoke(set)) return null;
            Method iterator = set.getClass().getMethod("iterator");
            Object iter = iterator.invoke(set);
            Method hasNext = iter.getClass().getMethod("hasNext");
            Method next = iter.getClass().getMethod("next");
            if ((boolean) hasNext.invoke(iter)) return next.invoke(iter);
        } catch (Exception e) {
            log("LiveBeansView failed: " + e.getClass().getSimpleName());
        }
        return null;
    }

    // ================ 工具方法 ================

    /**
     * 解包 MBean/MXBean 获取实际 Java 对象
     */
    public static Object unwrapMBean(Object mbean) {
        Object resource = getFieldValueRecursive(mbean, "resource");
        if (resource != null && resource != mbean) return resource;
        Object impl = getFieldValueRecursive(mbean, "implementation");
        if (impl != null && impl != mbean) return impl;
        impl = getFieldValueRecursive(mbean, "managedResource");
        if (impl != null) return impl;
        return mbean;
    }

    /**
     * 在对象的所有字段（含父类）中深入查找 ApplicationContext。
     * 支持直接类型匹配、Collection 元素匹配、Map value 匹配。
     */
    static Object findContextDeep(Object obj, Class<?> acClass) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : safeGetDeclaredFields(clazz)) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (acClass.isInstance(val) && isContextActive(val)) return val;
                    if (val instanceof Iterable) {
                        for (Object item : (Iterable<?>) val) {
                            if (item != null && acClass.isInstance(item) && isContextActive(item))
                                return item;
                        }
                    }
                    if (val instanceof Map) {
                        for (Object item : ((Map<?, ?>) val).values()) {
                            if (item != null && acClass.isInstance(item) && isContextActive(item))
                                return item;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * 递归向上查找字段值（含父类）
     */
    public static Object getFieldValueRecursive(Object obj, String fieldName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private static Field[] safeGetDeclaredFields(Class<?> clazz) {
        try {
            return clazz.getDeclaredFields();
        } catch (Throwable t) {
            return new Field[0];
        }
    }

    /**
     * 检查 ApplicationContext 是否活跃
     */
    public static boolean isContextActive(Object ctx) {
        try {
            Method isActive = ctx.getClass().getMethod("isActive");
            return Boolean.TRUE.equals(isActive.invoke(ctx));
        } catch (Exception e) {
            return true;
        }
    }
}
