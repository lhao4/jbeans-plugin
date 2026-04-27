package com.jbeans.debug.agent.handler;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 统一的Bean查找工具- 供 InvokeHandler 和 BeanResolveHandler
 */
public class BeanLocator {

    /**
     * 在Spring ApplicationContext 中查找Bean
     * @param ctx ApplicationContext（通过反射操作）
     * @param appCl 应用ClassLoader
     * @param className 类全限定名
     * @return 实例，找不到返回null
     */
    public static Object findBean(Object ctx, ClassLoader appCl, String className) {
        // 策略1: getBeansOfType(targetClass)
        try {
            Class<?> clazz = Class.forName(className, true, appCl);
            Method getBeansOfType = ctx.getClass().getMethod("getBeansOfType", Class.class);
            //noinspection unchecked
            Map<String, ?> beans = (Map<String, ?>) getBeansOfType.invoke(ctx, clazz);
            if (beans != null && !beans.isEmpty()) {
                return beans.values().iterator().next();
            }
        } catch (Exception ignored) {
        }

        // 策略2: containsBean(全限定名) → getBean
        try {
            Method containsBean = ctx.getClass().getMethod("containsBean", String.class);
            if (Boolean.TRUE.equals(containsBean.invoke(ctx, className))) {
                Method getBean = ctx.getClass().getMethod("getBean", String.class);
                return getBean.invoke(ctx, className);
            }
        } catch (Exception ignored) {
        }

        // 策略3: simpleName 首字母小写（Spring 默认命名策略）
        int dot = className.lastIndexOf('.');
        if (dot >= 0 && dot < className.length() - 1) {
            String simpleName = className.substring(dot + 1);
            String candidate = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
            try {
                Method containsBean = ctx.getClass().getMethod("containsBean", String.class);
                if (Boolean.TRUE.equals(containsBean.invoke(ctx, candidate))) {
                    Method getBean = ctx.getClass().getMethod("getBean", String.class);
                    return getBean.invoke(ctx, candidate);
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}
