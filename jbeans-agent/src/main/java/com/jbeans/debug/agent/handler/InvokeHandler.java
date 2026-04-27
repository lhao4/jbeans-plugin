package com.jbeans.debug.agent.handler;

import com.jbeans.debug.agent.JsonUtil;
import com.jbeans.debug.agent.SpringContextLocator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InvokeHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Object ctx = SpringContextLocator.getContext();
        if (ctx == null) {
            HandlerUtil.sendJson(exchange, 503,
                    "{\"error\":\"Spring ApplicationContext not ready\"}");
            return;
        }

        String body = HandlerUtil.readRequestBody(exchange);
        ClassLoader appCl = ctx.getClass().getClassLoader();
        JsonUtil jsonUtil = new JsonUtil(appCl);

        // 解析请求
        String interfaceName = null;
        String methodName = null;
        List<String> parameterTypes = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (jsonUtil.isAvailable()) {
            Map<String, Object> reqMap = jsonUtil.parseJsonToMap(body);
            if (reqMap != null) {
                interfaceName = (String) reqMap.get("interfaceName");
                methodName = (String) reqMap.get("methodName");
                Object pt = reqMap.get("parameterTypes");
                if (pt instanceof List<?>) {
                    for (Object o : (List<?>) pt) {
                        parameterTypes.add(String.valueOf(o));
                    }
                }
                Object a = reqMap.get("args");
                if (a instanceof List<?>) {
                    args = (List<Object>) a;
                }
            }
        }

        if (interfaceName == null || methodName == null) {
            sendError(exchange, jsonUtil, "INVALID_REQUEST", "Missing interfaceName or methodName");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            Object bean = findBean(ctx, appCl, interfaceName);
            Method method = resolveMethod(bean.getClass(), methodName, parameterTypes, appCl);
            Object[] convertedArgs = convertArgs(args, method.getGenericParameterTypes(), jsonUtil);
            method.setAccessible(true);
            Object result = method.invoke(bean, convertedArgs);

            String resultType = method.getReturnType() == Void.TYPE
                    ? "void" : method.getReturnType().getName();
            long elapsed = System.currentTimeMillis() - start;

            sendSuccess(exchange, jsonUtil, result, resultType, elapsed);

        } catch (InvokeException e) {
            sendError(exchange, jsonUtil, e.code, e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            sendError(exchange, jsonUtil, "INVOCATION_FAILED",
                    safeMessage(cause), stackTrace(cause));
        } catch (Exception e) {
            sendError(exchange, jsonUtil, "INTERNAL_ERROR",
                    safeMessage(e), stackTrace(e));
        }
    }

    // ==================== Bean 查找 ====================
    private Object findBean(Object ctx, ClassLoader appCl, String interfaceName) {
        Object bean = BeanLocator.findBean(ctx, appCl, interfaceName);
        if (bean == null) {
            throw new InvokeException("SERVICE_NOT_FOUND", "Service bean not found: " + interfaceName);
        }
        return bean;
    }

    // ==================== 方法解析 ====================
    private Method resolveMethod(Class<?> beanClass, String methodName,
                                 List<String> parameterTypes, ClassLoader appCl) {
        // 精确匹配
        try {
            Class<?>[] paramClasses = new Class<?>[parameterTypes.size()];
            for (int i = 0; i < paramClasses.length; i++) {
                paramClasses[i] = resolveType(parameterTypes.get(i), appCl);
            }
            return beanClass.getMethod(methodName, paramClasses);
        } catch (Exception ignored) {}

        // 回退扫描（支持基本类型/包装类型互转匹配）
        for (Method method : beanClass.getMethods()) {
            if (!method.getName().equals(methodName)) continue;
            Class<?>[] actualTypes = method.getParameterTypes();
            if (parameterTypes.isEmpty() && actualTypes.length == 0) return method;
            if (actualTypes.length != parameterTypes.size()) continue;

            boolean matched = true;
            for (int i = 0; i < actualTypes.length; i++) {
                if (!isTypeMatch(actualTypes[i], parameterTypes.get(i))) {
                    matched = false;
                    break;
                }
            }
            if (matched) return method;
        }

        throw new InvokeException("METHOD_NOT_FOUND",
                "Method not found: " + methodName + " with " + parameterTypes.size() + " params");
    }

    /**
     * 判断方法实际参数类型与传入的类型名是否匹配。
     * 支持基本类型与包装类型互转（如 int ↔ java.lang.Integer）。
     */
    private boolean isTypeMatch(Class<?> actualType, String requestedTypeName) {
        // 完全匹配
        if (actualType.getName().equals(requestedTypeName)) return true;
        // 基本类型 ↔ 包装类型
        Class<?> wrapped = wrapPrimitive(actualType);
        Class<?> unwrapped = unwrapPrimitive(actualType);
        if (wrapped != null && wrapped.getName().equals(requestedTypeName)) return true;
        if (unwrapped != null && unwrapped.getName().equals(requestedTypeName)) return true;
        return false;
    }

    /** 基本类型 → 包装类型 */
    private Class<?> wrapPrimitive(Class<?> type) {
        if (type == Byte.TYPE)      return Byte.class;
        if (type == Short.TYPE)     return Short.class;
        if (type == Integer.TYPE)   return Integer.class;
        if (type == Long.TYPE)      return Long.class;
        if (type == Float.TYPE)     return Float.class;
        if (type == Double.TYPE)    return Double.class;
        if (type == Boolean.TYPE)   return Boolean.class;
        if (type == Character.TYPE) return Character.class;
        return null;
    }

    /** 包装类型 → 基本类型 */
    private Class<?> unwrapPrimitive(Class<?> type) {
        if (type == Byte.class)      return Byte.TYPE;
        if (type == Short.class)     return Short.TYPE;
        if (type == Integer.class)   return Integer.TYPE;
        if (type == Long.class)      return Long.TYPE;
        if (type == Float.class)     return Float.TYPE;
        if (type == Double.class)    return Double.TYPE;
        if (type == Boolean.class)   return Boolean.TYPE;
        if (type == Character.class) return Character.TYPE;
        return null;
    }

    private Class<?> resolveType(String typeName, ClassLoader appCl) throws ClassNotFoundException {
        switch (typeName) {
            case "byte":    return Byte.TYPE;
            case "short":   return Short.TYPE;
            case "int":     return Integer.TYPE;
            case "long":    return Long.TYPE;
            case "float":   return Float.TYPE;
            case "double":  return Double.TYPE;
            case "boolean": return Boolean.TYPE;
            case "char":    return Character.TYPE;
            case "void":    return Void.TYPE;
            default:        return Class.forName(typeName, true, appCl);
        }
    }

    // ==================== 参数转换 ====================
    private Object[] convertArgs(List<Object> args, Type[] genericTypes, JsonUtil jsonUtil) {
        if (genericTypes.length == 0) return new Object[0];
        if (args == null || args.size() != genericTypes.length) {
            throw new InvokeException("PARAM_DESERIALIZE_FAILED",
                    "Args count mismatch: expected " + genericTypes.length
                            + ", got " + (args == null ? 0 : args.size()));
        }

        Object[] converted = new Object[genericTypes.length];
        for (int i = 0; i < genericTypes.length; i++) {
            try {
                converted[i] = jsonUtil.convertArg(args.get(i), genericTypes[i]);
            } catch (RuntimeException e) {
                throw new InvokeException("PARAM_DESERIALIZE_FAILED",
                        "Arg[" + i + "] conversion failed: " + e.getMessage());
            }
        }
        return converted;
    }

    // ==================== 响应构建 ====================
    private void sendSuccess(HttpExchange exchange, JsonUtil jsonUtil,
                             Object result, String resultType, long elapsed) throws IOException {
        if (jsonUtil.isAvailable()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("result", result);
            resp.put("resultType", resultType);
            resp.put("elapsedMs", elapsed);
            HandlerUtil.sendJson(exchange, 200, jsonUtil.toJson(resp));
        } else {
            String json = "{\"success\":true,\"result\":"
                    + (result == null ? "null" : "\"" + JsonUtil.escapeJson(String.valueOf(result)) + "\"")
                    + ",\"resultType\":\"" + JsonUtil.escapeJson(resultType) + "\""
                    + ",\"elapsedMs\":" + elapsed + "}";
            HandlerUtil.sendJson(exchange, 200, json);
        }
    }

    private void sendError(HttpExchange exchange, JsonUtil jsonUtil,
                           String code, String message) throws IOException {
        sendError(exchange, jsonUtil, code, message, null);
    }

    private void sendError(HttpExchange exchange, JsonUtil jsonUtil,
                           String code, String message, String trace) throws IOException {
        if (jsonUtil != null && jsonUtil.isAvailable()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", false);
            resp.put("error", code);
            resp.put("message", message);
            if (trace != null) resp.put("stackTrace", trace);
            HandlerUtil.sendJson(exchange, 400, jsonUtil.toJson(resp));
        } else {
            String json = "{\"success\":false"
                    + ",\"error\":\"" + JsonUtil.escapeJson(code) + "\""
                    + ",\"message\":\"" + JsonUtil.escapeJson(message) + "\""
                    + (trace != null ? ",\"stackTrace\":\"" + JsonUtil.escapeJson(trace) + "\"" : "")
                    + "}";
            HandlerUtil.sendJson(exchange, 400, json);
        }
    }

    // ==================== 工具 ====================
    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? t.getClass().getName() : msg;
    }

    private String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static class InvokeException extends RuntimeException {
        final String code;
        InvokeException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
