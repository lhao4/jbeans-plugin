package com.github.lhao4.jbeans.agent;

import java.lang.reflect.Method;
import java.net.URLDecoder;

class InvokeHandler {

    // query: bean=...&method=...&paramNames=a,b&paramTypes=A,B[&static=true]
    // body:  {"a":..., "b":...}
    static String handle(String query, String body) {
        try {
            if ("true".equals(param(query, "static"))) {
                return StaticInvokeHandler.handle(query, body);
            }
            String beanFqn = param(query, "bean");
            String methodName = param(query, "method");
            String rawParamNames = param(query, "paramNames");
            String rawParamTypes = param(query, "paramTypes");

            String[] paramNames = rawParamNames.isEmpty() ? new String[0] : rawParamNames.split(",");
            String[] paramTypes = rawParamTypes.isEmpty() ? new String[0] : rawParamTypes.split(",");

            Object ctx = ContextFinder.find();
            Object result = MethodInvoker.invoke(ctx, beanFqn, methodName, paramTypes, paramNames, body);
            String resultJson = serialize(ctx.getClass().getClassLoader(), result);
            return "{\"success\":true,\"result\":" + resultJson + "}";
        } catch (Exception e) {
            Throwable root = rootCause(e);
            String exType = root.getClass().getSimpleName();
            return "{\"success\":false,\"error\":\"" + AgentServer.escape(fullMsg(e)) +
                    "\",\"exceptionType\":\"" + AgentServer.escape(exType) + "\"}";
        }
    }

    private static String serialize(ClassLoader cl, Object result) {
        if (result == null) return "null";
        try {
            Class<?> mapperClass = cl.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperClass.getDeclaredConstructor().newInstance();
            // Enable pretty printing
            Class<?> featuresClass = cl.loadClass("com.fasterxml.jackson.databind.SerializationFeature");
            Object indentFeature = featuresClass.getField("INDENT_OUTPUT").get(null);
            Method enable = mapperClass.getMethod("enable",
                    cl.loadClass("com.fasterxml.jackson.databind.SerializationFeature"));
            enable.invoke(mapper, indentFeature);
            Method write = mapperClass.getMethod("writeValueAsString", Object.class);
            return (String) write.invoke(mapper, result);
        } catch (Exception e) {
            return "\"" + AgentServer.escape(String.valueOf(result)) + "\"";
        }
    }

    static String param(String query, String key) throws Exception {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
            if (k.equals(key)) return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
        }
        return "";
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur;
    }

    private static String fullMsg(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
        }
        return sb.toString();
    }
}
