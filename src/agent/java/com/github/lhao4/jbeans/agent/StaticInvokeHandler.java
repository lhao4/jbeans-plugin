package com.github.lhao4.jbeans.agent;

import java.lang.reflect.Method;

class StaticInvokeHandler {

    static String handle(String query, String body) {
        try {
            String classFqn    = InvokeHandler.param(query, "bean");
            String methodName  = InvokeHandler.param(query, "method");
            String rawNames    = InvokeHandler.param(query, "paramNames");
            String rawTypes    = InvokeHandler.param(query, "paramTypes");

            String[] paramNames = rawNames.isEmpty() ? new String[0] : rawNames.split(",");
            String[] paramTypes = rawTypes.isEmpty() ? new String[0] : rawTypes.split(",");

            ClassLoader cl = resolveClassLoader(classFqn);
            Class<?> clazz = cl.loadClass(classFqn);
            Class<?>[] resolvedTypes = resolveTypes(paramTypes, cl);

            Method method = findStaticMethod(clazz, methodName, resolvedTypes);
            method.setAccessible(true);

            Object[] args = deserializeArgs(body, paramNames, resolvedTypes, cl);
            Object result = method.invoke(null, args);

            String resultJson = serializeResult(cl, result);
            return "{\"success\":true,\"result\":" + resultJson + "}";
        } catch (Exception e) {
            Throwable root = rootCause(e);
            return "{\"success\":false,\"error\":\"" + AgentServer.escape(fullMsg(e)) +
                    "\",\"exceptionType\":\"" + AgentServer.escape(root.getClass().getSimpleName()) + "\"}";
        }
    }

    private static ClassLoader resolveClassLoader(String classFqn) {
        // Prefer Spring context classloader so Jackson and app classes are available
        try {
            Object ctx = ContextFinder.find();
            ClassLoader cl = ctx.getClass().getClassLoader();
            cl.loadClass(classFqn); // verify it can see the target class
            return cl;
        } catch (Exception ignored) {}
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) return tccl;
        return ClassLoader.getSystemClassLoader();
    }

    private static Method findStaticMethod(Class<?> clazz, String name, Class<?>[] paramTypes) throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException("Static method not found: " + clazz.getName() + "." + name);
    }

    private static Object[] deserializeArgs(String argsJson, String[] paramNames,
                                            Class<?>[] paramTypes, ClassLoader cl) throws Exception {
        if (paramTypes.length == 0) return new Object[0];
        Object mapper = newMapper(cl);
        Class<?> mapperClass = mapper.getClass();
        java.lang.reflect.Method readTree = mapperClass.getMethod("readTree", String.class);
        Object root = readTree.invoke(mapper, argsJson);
        Class<?> nodeClass = cl.loadClass("com.fasterxml.jackson.databind.JsonNode");
        java.lang.reflect.Method get = nodeClass.getMethod("get", String.class);
        java.lang.reflect.Method treeToValue = mapperClass.getMethod("treeToValue", nodeClass, Class.class);
        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Object fieldNode = get.invoke(root, paramNames[i]);
            result[i] = treeToValue.invoke(mapper, fieldNode, paramTypes[i]);
        }
        return result;
    }

    private static Object newMapper(ClassLoader cl) throws Exception {
        Class<?> mapperClass = cl.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
        return mapperClass.getDeclaredConstructor().newInstance();
    }

    private static Class<?>[] resolveTypes(String[] typeNames, ClassLoader cl) throws ClassNotFoundException {
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) types[i] = resolveOne(typeNames[i], cl);
        return types;
    }

    private static Class<?> resolveOne(String name, ClassLoader cl) throws ClassNotFoundException {
        if ("int".equals(name))     return int.class;
        if ("long".equals(name))    return long.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("double".equals(name))  return double.class;
        if ("float".equals(name))   return float.class;
        if ("byte".equals(name))    return byte.class;
        if ("short".equals(name))   return short.class;
        if ("char".equals(name))    return char.class;
        if ("void".equals(name))    return void.class;
        return cl.loadClass(name);
    }

    private static String serializeResult(ClassLoader cl, Object result) {
        if (result == null) return "null";
        try {
            Class<?> mapperClass = cl.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperClass.getDeclaredConstructor().newInstance();
            Class<?> featuresClass = cl.loadClass("com.fasterxml.jackson.databind.SerializationFeature");
            Object indentFeature = featuresClass.getField("INDENT_OUTPUT").get(null);
            mapperClass.getMethod("enable", featuresClass).invoke(mapper, indentFeature);
            return (String) mapperClass.getMethod("writeValueAsString", Object.class).invoke(mapper, result);
        } catch (Exception e) {
            return "\"" + AgentServer.escape(String.valueOf(result)) + "\"";
        }
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
