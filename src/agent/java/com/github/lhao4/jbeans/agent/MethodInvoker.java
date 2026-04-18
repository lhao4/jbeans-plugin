package com.github.lhao4.jbeans.agent;

import java.lang.reflect.Method;

class MethodInvoker {

    static Object invoke(Object ctx, String beanFqn, String methodName,
                         String[] paramTypes, String[] paramNames, String argsJson) throws Exception {
        ClassLoader cl = ctx.getClass().getClassLoader();

        Class<?> beanClass = cl.loadClass(beanFqn);
        Method getBean = findGetBean(ctx.getClass());
        Object bean = getBean.invoke(ctx, beanClass);

        Class<?>[] resolvedTypes = resolveTypes(paramTypes, cl);
        Method method = findMethod(beanClass, bean.getClass(), methodName, resolvedTypes);
        method.setAccessible(true);

        Object[] args = deserializeArgs(argsJson, paramNames, resolvedTypes, cl);
        return method.invoke(bean, args);
    }

    private static Object[] deserializeArgs(String argsJson, String[] paramNames,
                                             Class<?>[] paramTypes, ClassLoader cl) throws Exception {
        if (paramTypes.length == 0) return new Object[0];
        // Use Jackson ObjectMapper from target app's classpath
        Object mapper = newMapper(cl);
        Class<?> mapperClass = mapper.getClass();
        Method readTree = mapperClass.getMethod("readTree", String.class);
        Object root = readTree.invoke(mapper, argsJson);

        Class<?> nodeClass = cl.loadClass("com.fasterxml.jackson.databind.JsonNode");
        Method get = nodeClass.getMethod("get", String.class);
        Method treeToValue = mapperClass.getMethod("treeToValue", nodeClass, Class.class);

        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Object fieldNode = get.invoke(root, paramNames[i]);
            result[i] = treeToValue.invoke(mapper, fieldNode, paramTypes[i]);
        }
        return result;
    }

    private static Object newMapper(ClassLoader cl) throws Exception {
        // Try Jackson 2.x
        try {
            Class<?> mapperClass = cl.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
            return mapperClass.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new Exception("Jackson ObjectMapper not found on target app classpath");
        }
    }

    private static Method findGetBean(Class<?> ctxClass) throws NoSuchMethodException {
        Class<?> c = ctxClass;
        while (c != null) {
            try { return c.getMethod("getBean", Class.class); }
            catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        // Try interfaces
        for (Class<?> iface : ctxClass.getInterfaces()) {
            try { return iface.getMethod("getBean", Class.class); }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException("getBean(Class)");
    }

    private static Method findMethod(Class<?> beanClass, Class<?> implClass,
                                      String name, Class<?>[] paramTypes) {
        // Try declared interface / class first, then impl
        for (Class<?> c : new Class<?>[]{beanClass, implClass}) {
            try { return c.getMethod(name, paramTypes); }
            catch (NoSuchMethodException ignored) {}
        }
        // Deep search including private/protected
        Class<?> c = implClass;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        throw new RuntimeException("Method not found: " + name);
    }

    private static Class<?>[] resolveTypes(String[] typeNames, ClassLoader cl) throws ClassNotFoundException {
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = resolveOne(typeNames[i], cl);
        }
        return types;
    }

    private static Class<?> resolveOne(String name, ClassLoader cl) throws ClassNotFoundException {
        return switch (name) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "boolean" -> boolean.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "void" -> void.class;
            default -> cl.loadClass(name);
        };
    }
}
