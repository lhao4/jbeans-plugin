package com.github.lhao4.jbeans.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

class ContextFinder {

    static Object find() throws Exception {
        ClassLoader cl = findSpringClassLoader();
        if (cl == null) throw new Exception("Spring ClassLoader not found in running threads");

        // Strategy 1: ContextLoader (Spring Web)
        try {
            Class<?> loaderClass = cl.loadClass("org.springframework.web.context.ContextLoader");
            Method m = loaderClass.getMethod("getCurrentWebApplicationContext");
            Object ctx = m.invoke(null);
            if (ctx != null) return ctx;
        } catch (Exception ignored) {}

        // Strategy 2: SpringApplicationShutdownHook stores contexts (Spring Boot 2.3+)
        try {
            Class<?> appHooks = Class.forName("java.lang.ApplicationShutdownHooks");
            Field hooksField = appHooks.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Thread, Thread> hooks = (Map<Thread, Thread>) hooksField.get(null);
            for (Thread hook : hooks.keySet()) {
                if (hook.getClass().getName().contains("SpringApplicationShutdownHook")) {
                    try {
                        Field ctxsField = hook.getClass().getDeclaredField("contexts");
                        ctxsField.setAccessible(true);
                        Iterable<?> ctxs = (Iterable<?>) ctxsField.get(hook);
                        Object ctx = ctxs.iterator().next();
                        if (ctx != null) return ctx;
                    } catch (Exception ignored2) {}
                }
            }
        } catch (Exception ignored) {}

        throw new Exception("ApplicationContext not found. Ensure the app has started fully.");
    }

    private static ClassLoader findSpringClassLoader() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = t.getContextClassLoader();
            if (cl == null) continue;
            try {
                cl.loadClass("org.springframework.context.ApplicationContext");
                return cl;
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }
}
