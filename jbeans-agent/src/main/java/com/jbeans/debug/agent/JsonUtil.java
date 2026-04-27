package com.jbeans.debug.agent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具 - 优先反射调用目标 JVM 的 Jackson，不可用时回退手写。
 * <p>
 * Agent 不能直接依赖 Jackson（ClassLoader 隔离），通过反射发现和调用。
 */
public final class JsonUtil {

    private final Object objectMapper;
    private final Method writeValueAsString;
    private final Method readValue;
    private final Method readValueJavaType;
    private final Method convertValue;
    private final Method constructType;
    private final Object typeFactory;

    public JsonUtil(ClassLoader appCl) {
        Object om = null;
        Method write = null;
        Method read = null;
        Method readJavaType = null;
        Method convert = null;
        Method construct = null;
        Object tf = null;

        try {
            Class<?> omClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, appCl);
            om = omClass.getDeclaredConstructor().newInstance();

            // 注册 JavaTimeModule（支持 LocalDateTime 等 Java 8 时间类型）
            try {
                Class<?> jtmClass = Class.forName("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule", true, appCl);
                Object jtm = jtmClass.getDeclaredConstructor().newInstance();
                Class<?> moduleClass = Class.forName("com.fasterxml.jackson.databind.Module", true, appCl);
                Method registerModule = omClass.getMethod("registerModule", moduleClass);
                registerModule.invoke(om, jtm);
            } catch (ClassNotFoundException ignored) {
                // JavaTimeModule 不在 classpath 中，跳过
            }

            // 配置: WRITE_DATES_AS_TIMESTAMPS=false（输出 ISO-8601 字符串）
            try {
                Class<?> sfClass = Class.forName("com.fasterxml.jackson.databind.SerializationFeature", true, appCl);
                Method configure = omClass.getMethod("configure", sfClass, boolean.class);
                Object writeDatesAsTs = Enum.valueOf((Class<Enum>) sfClass, "WRITE_DATES_AS_TIMESTAMPS");
                configure.invoke(om, writeDatesAsTs, false);
                // FAIL_ON_EMPTY_BEANS=false
                Object failOnEmpty = Enum.valueOf((Class<Enum>) sfClass, "FAIL_ON_EMPTY_BEANS");
                configure.invoke(om, failOnEmpty, false);
            } catch (Exception ignored) {}

            Class<?> javaTypeClass = Class.forName("com.fasterxml.jackson.databind.JavaType", true, appCl);

            write = omClass.getMethod("writeValueAsString", Object.class);
            read = omClass.getMethod("readValue", String.class, Class.class);
            readJavaType = omClass.getMethod("readValue", String.class, javaTypeClass);
            convert = omClass.getMethod("convertValue", Object.class, javaTypeClass);

            Method getTF = omClass.getMethod("getTypeFactory");
            tf = getTF.invoke(om);
            construct = tf.getClass().getMethod("constructType", java.lang.reflect.Type.class);

        } catch (Exception ignored) {
            om = null;
        }

        this.objectMapper = om;
        this.writeValueAsString = write;
        this.readValue = read;
        this.readValueJavaType = readJavaType;
        this.convertValue = convert;
        this.constructType = construct;
        this.typeFactory = tf;
    }

    public boolean isAvailable() {
        return objectMapper != null;
    }

    /** 序列化对象为 JSON 字符串 */
    public String toJson(Object obj) {
        if (objectMapper == null) return "null";
        try {
            return (String) writeValueAsString.invoke(objectMapper, obj);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "{\"serialization_error\":\"" + escapeJson(cause.getMessage()) + "\"}";
        }
    }

    /** 反序列化 JSON 为指定类型 */
    public Object fromJson(String json, Class<?> type) {
        if (objectMapper == null) return null;
        try {
            return readValue.invoke(objectMapper, json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 使用 Jackson 转换参数到目标类型（支持泛型）。
     * 策略: convertValue → serialize/deserialize → 原样返回 + 异常信息
     */
    public Object convertArg(Object arg, java.lang.reflect.Type genericType) {
        if (arg == null) return null;
        if (objectMapper == null || constructType == null) return arg;

        Exception firstError = null;
        try {
            Object javaType = constructType.invoke(typeFactory, genericType);

            // 策略 1: convertValue（直接 Map→Object 转换）
            if (convertValue != null) {
                try {
                    return convertValue.invoke(objectMapper, arg, javaType);
                } catch (Exception e) {
                    firstError = e;
                }
            }

            // 策略 2: serialize → deserialize（经过 JSON 中转）
            if (readValueJavaType != null && writeValueAsString != null) {
                try {
                    String json = (String) writeValueAsString.invoke(objectMapper, arg);
                    return readValueJavaType.invoke(objectMapper, json, javaType);
                } catch (Exception e) {
                    if (firstError == null) firstError = e;
                }
            }
        } catch (Exception e) {
            if (firstError == null) firstError = e;
        }

        // 所有策略都失败，包装异常抛出，让调用方感知
        if (firstError != null) {
            Throwable cause = firstError.getCause() != null ? firstError.getCause() : firstError;
            throw new RuntimeException("Cannot convert arg [" + arg.getClass().getName()
                    + "] to [" + genericType + "]: " + cause.getMessage(), cause);
        }

        return arg;
    }

    /** 反序列化 JSON 为 Map（通用解析） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseJsonToMap(String json) {
        if (objectMapper == null) return null;
        try {
            return (Map<String, Object>) readValue.invoke(objectMapper, json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 无 Jackson 时的手写 fallback ====================

    /** JSON 字符串转义 */
    public static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** 手写简易 JSON 序列化（Map/List/String/Number/Boolean/null） */
    public static String simpleToJson(Map<String, Object> map) {
        if (map == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            appendValue(sb, entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object val) {
        if (val == null) {
            sb.append("null");
        } else if (val instanceof String) {
            sb.append("\"").append(escapeJson((String) val)).append("\"");
        } else if (val instanceof Number || val instanceof Boolean) {
            sb.append(val);
        } else if (val instanceof Map) {
            sb.append(simpleToJson((Map<String, Object>) val));
        } else if (val instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object item : (List<?>) val) {
                if (!first) sb.append(",");
                first = false;
                appendValue(sb, item);
            }
            sb.append("]");
        } else {
            sb.append("\"").append(escapeJson(val.toString())).append("\"");
        }
    }
}
