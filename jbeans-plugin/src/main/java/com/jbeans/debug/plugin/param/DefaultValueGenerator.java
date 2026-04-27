package com.jbeans.debug.plugin.param;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.psi.*;
import java.util.*;

/**
 * 根据 PSI 类型生成方法参数的默认值 JSON，用于 JBeans 调用测试。
 */
public class DefaultValueGenerator {

    private static final int MAX_DEPTH = 5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, Object> PRIMITIVE_DEFAULTS = new HashMap<>();
    private static final Map<String, String> DATE_DEFAULTS = new HashMap<>();
    private static final Set<String> LIST_TYPES = new HashSet<>();
    private static final Set<String> MAP_TYPES = new HashSet<>();

    static {
        // 基本类型 & 包装类型
        PRIMITIVE_DEFAULTS.put("int", 0);
        PRIMITIVE_DEFAULTS.put("long", 0);
        PRIMITIVE_DEFAULTS.put("short", 0);
        PRIMITIVE_DEFAULTS.put("byte", 0);
        PRIMITIVE_DEFAULTS.put("float", 0.0);
        PRIMITIVE_DEFAULTS.put("double", 0.0);
        PRIMITIVE_DEFAULTS.put("boolean", false);
        PRIMITIVE_DEFAULTS.put("char", "");

        PRIMITIVE_DEFAULTS.put("java.lang.Integer", 0);
        PRIMITIVE_DEFAULTS.put("java.lang.Long", 0);
        PRIMITIVE_DEFAULTS.put("java.lang.Short", 0);
        PRIMITIVE_DEFAULTS.put("java.lang.Byte", 0);
        PRIMITIVE_DEFAULTS.put("java.lang.Float", 0.0);
        PRIMITIVE_DEFAULTS.put("java.lang.Double", 0.0);
        PRIMITIVE_DEFAULTS.put("java.lang.Boolean", false);
        PRIMITIVE_DEFAULTS.put("java.lang.Character", "");
        PRIMITIVE_DEFAULTS.put("java.lang.String", "");
        PRIMITIVE_DEFAULTS.put("java.math.BigDecimal", 0);
        PRIMITIVE_DEFAULTS.put("java.math.BigInteger", 0);

        // 日期类型
        DATE_DEFAULTS.put("java.util.Date", "2026-01-01 00:00:00");
        DATE_DEFAULTS.put("java.time.LocalDateTime", "2026-01-01T00:00:00");
        DATE_DEFAULTS.put("java.time.LocalDate", "2026-01-01");
        DATE_DEFAULTS.put("java.time.LocalTime", "00:00:00");
        DATE_DEFAULTS.put("java.time.Instant", "2026-01-01T00:00:00Z");

        // 集合类型
        LIST_TYPES.addAll(Arrays.asList(
                "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
                "java.util.Set", "java.util.HashSet", "java.util.TreeSet",
                "java.util.Collection"
        ));
        MAP_TYPES.addAll(Arrays.asList(
                "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap",
                "java.util.TreeMap", "java.util.ConcurrentHashMap"
        ));
    }

    /**
     * 为方法的所有参数生成默认 JSON。
     * 返回以参数名为 key 的 JSON 对象。
     */
    public static String generate(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        if (params.length == 0) {
            return "{}";
        }

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (PsiParameter param : params) {
            Object value = generateDefault(param.getType(), 0, new HashSet<>());
            result.put(param.getName(), value);
        }

        return GSON.toJson(result);
    }

    private static Object generateDefault(PsiType type, int depth, Set<String> visited) {
        // 递归深度保护
        if (depth > MAX_DEPTH) {
            return Collections.emptyMap();
        }

        // 基本类型
        if (type instanceof PsiPrimitiveType) {
            String name = type.getCanonicalText();
            Object val = PRIMITIVE_DEFAULTS.get(name);
            return val != null ? val : 0;
        }

        // 数组类型
        if (type instanceof PsiArrayType) {
            PsiType componentType = ((PsiArrayType) type).getComponentType();
            Object element = generateDefault(componentType, depth + 1, visited);
            return Collections.singletonList(element);
        }

        String canonicalText = type.getCanonicalText();
        // 移除泛型部分，便于查找
        String rawType = canonicalText.contains("<")
                ? canonicalText.substring(0, canonicalText.indexOf('<'))
                : canonicalText;

        // 基本/包装/String/BigDecimal
        if (PRIMITIVE_DEFAULTS.containsKey(rawType)) {
            return PRIMITIVE_DEFAULTS.get(rawType);
        }

        // 日期类型
        if (DATE_DEFAULTS.containsKey(rawType)) {
            return DATE_DEFAULTS.get(rawType);
        }

        // 非 PsiClassType，返回空 Map
        if (!(type instanceof PsiClassType)) {
            return Collections.emptyMap();
        }

        PsiClassType classType = (PsiClassType) type;
        PsiClass resolved = classType.resolve();
        if (resolved == null) {
            return Collections.emptyMap();
        }

        String qualifiedName = resolved.getQualifiedName();
        if (qualifiedName == null) {
            return Collections.emptyMap();
        }

        // 循环引用检测
        if (visited.contains(qualifiedName)) {
            return Collections.emptyMap();
        }

        // List / Set / Collection
        if (LIST_TYPES.contains(qualifiedName)) {
            PsiType[] typeParams = classType.getParameters();
            if (typeParams.length > 0) {
                Object element = generateDefault(typeParams[0], depth + 1, visited);
                return Collections.singletonList(element);
            }
            return Collections.emptyList();
        }

        // Map
        if (MAP_TYPES.contains(qualifiedName)) {
            PsiType[] typeParams = classType.getParameters();
            if (typeParams.length >= 2) {
                Object value = generateDefault(typeParams[1], depth + 1, visited);
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                map.put("", value);
                return map;
            }
            return Collections.emptyMap();
        }

        // 枚举
        if (resolved.isEnum()) {
            PsiField[] fields = resolved.getFields();
            for (PsiField field : fields) {
                if (field instanceof PsiEnumConstant) {
                    return field.getName();
                }
            }
            return "";
        }

        // 跳过 java.lang.Object 和其他框架类型
        if (qualifiedName.startsWith("java.")
                || qualifiedName.startsWith("javax.")
                || qualifiedName.startsWith("org.springframework.")) {
            return Collections.emptyMap();
        }

        // 自定义 POJO - 递归字段提取
        visited.add(qualifiedName);
        LinkedHashMap<String, Object> obj = new LinkedHashMap<>();

        for (PsiField field : resolved.getAllFields()) {
            PsiModifierList modifiers = field.getModifierList();
            if (modifiers == null) continue;
            if (modifiers.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (modifiers.hasModifierProperty(PsiModifier.TRANSIENT)) continue;

            // 如果是泛型类，先解析类型参数再获取字段类型
            PsiType fieldType = field.getType();
            if (type instanceof PsiClassType) {
                PsiSubstitutor substitutor = ((PsiClassType) type).resolveGenerics().getSubstitutor();
                fieldType = substitutor.substitute(fieldType);
                if (fieldType == null) {
                    fieldType = field.getType();
                }
            }

            obj.put(field.getName(), generateDefault(fieldType, depth + 1, visited));
        }

        visited.remove(qualifiedName);
        return obj;
    }
}
