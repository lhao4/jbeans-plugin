package com.jbeans.debug.agent.handler;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Schema 生成工具 – 递归构建 Java 类型的默认值 JSON 结构。
 * <p>
 * 供 SchemaHandler 和 BeanResolveHandler 共用，避免代码重复。
 * 支持基本类型、枚举、集合、Map、时间类型、嵌套 DTO，带循环引用检测。
 */
public final class SchemaUtil {

    private static final int MAX_DEPTH = 5;

    private SchemaUtil() {}

    /**
     * 递归构建类型的默认值对象。
     * 返回 Map（对象）、List（集合）、String/Number/Boolean（基本类型）。
     */
    public static Object buildDefaultValue(Type type, Class<?> rawClass,
                                           Set<String> visiting, int depth) {
        if (depth > MAX_DEPTH) return null;

        // 基本类型和包装类
        if (rawClass == String.class || rawClass == CharSequence.class) return "";
        if (rawClass == int.class || rawClass == Integer.class) return 0;
        if (rawClass == long.class || rawClass == Long.class) return 0L;
        if (rawClass == double.class || rawClass == Double.class) return 0.0;
        if (rawClass == float.class || rawClass == Float.class) return 0.0f;
        if (rawClass == boolean.class || rawClass == Boolean.class) return false;
        if (rawClass == short.class || rawClass == Short.class) return 0;
        if (rawClass == byte.class || rawClass == Byte.class) return 0;
        if (rawClass == char.class || rawClass == Character.class) return "";
        if (rawClass == BigDecimal.class) return 0.0;
        if (rawClass == BigInteger.class) return 0;

        // Java 时间类型
        if (rawClass == Date.class) return "2025-01-01T00:00:00.000+0800";
        if (rawClass.getName().equals("java.time.LocalDateTime")) return "2025-01-01T00:00:00";
        if (rawClass.getName().equals("java.time.LocalDate")) return "2025-01-01";
        if (rawClass.getName().equals("java.time.LocalTime")) return "00:00:00";

        // 枚举
        if (rawClass.isEnum()) {
            Object[] constants = rawClass.getEnumConstants();
            if (constants != null && constants.length > 0) return constants[0].toString();
            return "";
        }

        // 数组
        if (rawClass.isArray()) {
            Class<?> componentType = rawClass.getComponentType();
            Object item = buildDefaultValue(componentType, componentType, visiting, depth + 1);
            List<Object> list = new ArrayList<>();
            if (item != null) list.add(item);
            return list;
        }

        // 集合: List / Set / Collection
        if (Collection.class.isAssignableFrom(rawClass)) {
            List<Object> list = new ArrayList<>();
            Type elementType = extractGenericArg(type, 0);
            if (elementType != null) {
                Class<?> elementClass = toRawClass(elementType);
                if (elementClass != null) {
                    Object item = buildDefaultValue(elementType, elementClass, visiting, depth + 1);
                    if (item != null) list.add(item);
                }
            }
            return list;
        }

        // Map
        if (Map.class.isAssignableFrom(rawClass)) {
            Map<String, Object> map = new LinkedHashMap<>();
            Type valueType = extractGenericArg(type, 1);
            if (valueType != null) {
                Class<?> valueClass = toRawClass(valueType);
                if (valueClass != null) {
                    Object val = buildDefaultValue(valueType, valueClass, visiting, depth + 1);
                    map.put("key", val);
                }
            }
            return map;
        }

        // 复杂对象: 反射字段
        String className = rawClass.getName();
        if (className.startsWith("java.") || className.startsWith("javax.")
                || className.startsWith("sun.") || className.startsWith("jdk.")) {
            return null;
        }

        // 循环引用检测
        if (visiting.contains(className)) return "<<circular>>";
        visiting.add(className);

        Map<String, Object> obj = new LinkedHashMap<>();
        Class<?> current = rawClass;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (Modifier.isTransient(f.getModifiers())) continue;

                String fieldName = f.getName();
                if (obj.containsKey(fieldName)) continue;

                Type fieldGenericType = f.getGenericType();
                Class<?> fieldRawClass = f.getType();

                Object defaultVal = buildDefaultValue(fieldGenericType, fieldRawClass, visiting, depth + 1);
                obj.put(fieldName, defaultVal);
            }
            current = current.getSuperclass();
        }

        visiting.remove(className);
        return obj;
    }

    /** 提取泛型参数 (如 List<String> 的第 0 个是 String) */
    public static Type extractGenericArg(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (args.length > index) return args[index];
        }
        return null;
    }

    /** Type → Class (处理 ParameterizedType) */
    public static Class<?> toRawClass(Type type) {
        if (type instanceof Class) return (Class<?>) type;
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class) return (Class<?>) raw;
        }
        return null;
    }
}