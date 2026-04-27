package com.jbeans.debug.agent.handler;

import com.jbeans.debug.agent.JsonUtil;
import com.jbeans.debug.agent.SpringContextLocator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.*;

public class BeanResolveHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Object ctx = SpringContextLocator.getContext();
        if (ctx == null) {
            HandlerUtil.sendJson(exchange, 503, "{\"error\":\"Spring ApplicationContext not ready\"}");
            return;
        }

        String body = HandlerUtil.readRequestBody(exchange);
        ClassLoader appCl = ctx.getClass().getClassLoader();
        JsonUtil jsonUtil = new JsonUtil(appCl);

        String className = null;
        String methodName = null;

        if (jsonUtil.isAvailable()) {
            Map<String, Object> reqMap = jsonUtil.parseJsonToMap(body);
            if (reqMap != null) {
                className = (String) reqMap.get("className");
                methodName = (String) reqMap.get("methodName");
            }
        }

        if (className == null || methodName == null) {
            HandlerUtil.sendJson(exchange, 400, "{\"error\":\"Missing className or methodName\"}");
            return;
        }

        try {
            // 1. 验证 Bean 存在
            Class.forName(className, true, appCl); // 确认类存在
            Object bean = findBean(ctx, appCl, className);
            if (bean == null) {
                HandlerUtil.sendJson(exchange, 404,
                        "{\"error\":\"Bean not found for class: " + JsonUtil.escapeJson(className) + "\"}");
                return;
            }

            // 2. 查找所有匹配的方法（可能有重载）
            List<Method> methods = findPublicMethods(bean.getClass(), methodName);
            if (methods.isEmpty()) {
                HandlerUtil.sendJson(exchange, 404,
                        "{\"error\":\"Method not found: " + JsonUtil.escapeJson(methodName) + "\"}");
                return;
            }

            // 3. 构建响应
            List<Map<String, Object>> overloads = new ArrayList<>();
            for (Method m : methods) {
                overloads.add(buildMethodInfo(m, appCl));
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("className", className);
            resp.put("beanClass", bean.getClass().getName());
            resp.put("methodName", methodName);
            resp.put("overloads", overloads);
            HandlerUtil.sendJson(exchange, 200, jsonUtil.toJson(resp));

        } catch (ClassNotFoundException e) {
            HandlerUtil.sendJson(exchange, 404,
                    "{\"error\":\"Class not found: " + JsonUtil.escapeJson(className) + "\"}");
        } catch (Exception e) {
            HandlerUtil.sendJson(exchange, 500,
                    "{\"error\":\"" + JsonUtil.escapeJson(e.getMessage() != null ? e.getMessage() : e.getClass().getName()) + "\"}");
        }
    }

    // ==================== Bean 查找 ====================
    private Object findBean(Object ctx, ClassLoader appCl, String className) {
        return BeanLocator.findBean(ctx, appCl, className);
    }

    // ==================== 方法查找 ====================
    private List<Method> findPublicMethods(Class<?> beanClass, String methodName) {
        List<Method> result = new ArrayList<>();
        for (Method m : beanClass.getMethods()) {
            if (m.getName().equals(methodName) && !m.getDeclaringClass().equals(Object.class)) {
                result.add(m);
            }
        }
        return result;
    }

    // ==================== 构建方法信息 ====================
    private Map<String, Object> buildMethodInfo(Method method, ClassLoader appCl) {
        Map<String, Object> info = new LinkedHashMap<>();

        // 优先从接口方法获取参数名（接口通常保留参数名）
        Method sourceMethod = findInterfaceMethod(method);

        Class<?>[] paramTypes = method.getParameterTypes();
        Type[] genericTypes = method.getGenericParameterTypes();
        Parameter[] parameters = sourceMethod.getParameters();
        List<String> paramTypeNames = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        List<Object> paramSchemas = new ArrayList<>();

        for (int i = 0; i < paramTypes.length; i++) {
            paramTypeNames.add(paramTypes[i].getName());
            // 使用 Parameter.getName()，编译时带 -parameters 则返回真实名
            paramNames.add(i < parameters.length ? parameters[i].getName() : "arg" + i);

            Set<String> visiting = new HashSet<>();
            Object schema = SchemaUtil.buildDefaultValue(genericTypes[i], paramTypes[i], visiting, 0);
            paramSchemas.add(schema);
        }

        info.put("parameterTypes", paramTypeNames);
        info.put("parameterNames", paramNames);
        info.put("parameterSchemas", paramSchemas);
        info.put("returnType", method.getReturnType().getName());

        // 显示签名
        StringBuilder sig = new StringBuilder(method.getName()).append("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(simplifyType(paramTypes[i].getName())).append(" ").append(paramNames.get(i));
        }
        sig.append("): ").append(simplifyType(method.getReturnType().getName()));
        info.put("displaySignature", sig.toString());

        return info;
    }

    /**
     * 从方法的声明类的接口中查找同签名方法（接口通常保留参数名）。
     * 找不到则返回原方法。
     */
    private Method findInterfaceMethod(Method method) {
        Class<?> declaringClass = method.getDeclaringClass();
        for (Class<?> iface : declaringClass.getInterfaces()) {
            try {
                Method ifaceMethod = iface.getMethod(method.getName(), method.getParameterTypes());
                // 检查接口方法是否有真实参数名（非 argN）
                Parameter[] params = ifaceMethod.getParameters();
                if (params.length > 0 && !params[0].getName().matches("arg\\d+")) {
                    return ifaceMethod;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return method;
    }

    private String simplifyType(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx > 0 ? fqcn.substring(idx + 1) : fqcn;
    }
}
