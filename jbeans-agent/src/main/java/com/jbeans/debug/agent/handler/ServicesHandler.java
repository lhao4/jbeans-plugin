package com.jbeans.debug.agent.handler;

import com.jbeans.debug.agent.JsonUtil;
import com.jbeans.debug.agent.SpringContextLocator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jbeans.debug.agent.AgentLogger.log;

/**
 * GET /jbeans-debug/services - 服务发现端点。
 * 基于反射扫描服务接口，兼容常见 Dubbo 暴露方式。
 */
public class ServicesHandler implements HttpHandler {

    private static final String[] SERVICE_ANNOTATIONS = {
            "org.apache.dubbo.config.annotation.DubboService",
            "org.apache.dubbo.config.annotation.Service",
            "com.alibaba.dubbo.config.annotation.Service",
    };

    private static final String[] SERVICE_BEAN_CLASS_NAMES = {
            "org.apache.dubbo.config.spring.ServiceBean",
            "com.alibaba.dubbo.config.spring.ServiceBean",
    };

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Object ctx = SpringContextLocator.getContext();
        if (ctx == null) {
            HandlerUtil.sendJson(exchange, 503,
                    "{\"error\":\"Spring ApplicationContext not ready\"}");
            return;
        }

        ClassLoader appCl = ctx.getClass().getClassLoader();
        Map<String, Object> serviceMap = new LinkedHashMap<>();

        // 1) 扫描带服务注解的 Bean
        scanAnnotatedBeans(ctx, appCl, serviceMap);

        // 2) 扫描 ServiceBean
        scanServiceBeans(ctx, appCl, serviceMap);

        // 构建 JSON 响应
        List<Object> serviceList = new ArrayList<>(serviceMap.values());
        JsonUtil jsonUtil = new JsonUtil(appCl);

        String json;
        if (jsonUtil.isAvailable()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("services", serviceList);
            json = jsonUtil.toJson(result);
        } else {
            json = buildServicesJson(serviceList);
        }

        HandlerUtil.sendJson(exchange, 200, json);
    }

    @SuppressWarnings("unchecked")
    private void scanAnnotatedBeans(Object ctx, ClassLoader appCl,
                                    Map<String, Object> serviceMap) {
        // ctx.getBeansOfType(Object.class) – 带超时保护
        Map<String, ?> allBeans = invokeGetBeansOfType(ctx, Object.class, 5000);
        if (allBeans == null) return;

        for (Object bean : allBeans.values()) {
            Class<?> beanClass = bean.getClass();
            if (!isServiceBean(beanClass, appCl)) continue;

            for (Class<?> iface : beanClass.getInterfaces()) {
                String ifaceName = iface.getName();
                if (serviceMap.containsKey(ifaceName)) continue;
                serviceMap.put(ifaceName, buildServiceInfo(iface));
            }
        }
    }

    private void scanServiceBeans(Object ctx, ClassLoader appCl,
                                  Map<String, Object> serviceMap) {
        for (String className : SERVICE_BEAN_CLASS_NAMES) {
            try {
                Class<?> sbClass = Class.forName(className, false, appCl);
                Map<String, ?> beans = invokeGetBeansOfType(ctx, sbClass, 5000);
                if (beans == null || beans.isEmpty()) continue;

                for (Object serviceBean : beans.values()) {
                    try {
                        Method getInterfaceClass = serviceBean.getClass()
                                .getMethod("getInterfaceClass");
                        Object ifaceClassObj = getInterfaceClass.invoke(serviceBean);
                        if (ifaceClassObj instanceof Class) {
                            Class<?> iface = (Class<?>) ifaceClassObj;
                            String ifaceName = iface.getName();
                            if (!serviceMap.containsKey(ifaceName)) {
                                serviceMap.put(ifaceName, buildServiceInfo(iface));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                log("Error scanning ServiceBean [" + className + "]: " + e.getMessage());
            }
        }
    }

    private boolean isServiceBean(Class<?> beanClass, ClassLoader appCl) {
        for (String annName : SERVICE_ANNOTATIONS) {
            try {
                Class<?> annClass = Class.forName(annName, false, appCl);
                if (Annotation.class.isAssignableFrom(annClass)) {
                    if (beanClass.getAnnotation((Class<? extends Annotation>) annClass) != null) {
                        return true;
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * 带超时保护的 getBeansOfType 调用
     */
    @SuppressWarnings("unchecked")
    private Map<String, ?> invokeGetBeansOfType(Object ctx, Class<?> type, long timeoutMs) {
        final Object[] result = {null};
        final Exception[] error = {null};

        Thread worker = new Thread(() -> {
            try {
                Method m = ctx.getClass().getMethod("getBeansOfType", Class.class);
                result[0] = m.invoke(ctx, type);
            } catch (Exception e) {
                error[0] = e;
            }
        }, "jbeans-debug-scan");
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }

        if (worker.isAlive()) {
            log("getBeansOfType timed out for " + type.getName());
            worker.interrupt();
            return null;
        }

        if (error[0] != null) {
            log("getBeansOfType error: " + error[0].getMessage());
            return null;
        }

        return (Map<String, ?>) result[0];
    }

    /**
     * 构建单个服务的元信息 Map
     */
    private Map<String, Object> buildServiceInfo(Class<?> interfaceClass) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("interfaceName", interfaceClass.getName());

        List<Map<String, Object>> methods = new ArrayList<>();
        for (Method method : interfaceClass.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;

            Map<String, Object> mi = new LinkedHashMap<>();
            mi.put("name", method.getName());

            List<String> paramNames = new ArrayList<>();
            List<String> paramTypes = new ArrayList<>();
            for (Parameter p : method.getParameters()) {
                paramNames.add(p.getName());
                paramTypes.add(p.getType().getName());
            }

            mi.put("parameterNames", paramNames);
            mi.put("parameterTypes", paramTypes);
            mi.put("returnType", method.getReturnType().getName());

            methods.add(mi);
        }

        info.put("methods", methods);
        return info;
    }

    /**
     * Jackson 不可用时手写 JSON
     */
    private String buildServicesJson(List<Object> serviceList) {
        StringBuilder sb = new StringBuilder("{\"services\":[");
        boolean first = true;
        for (Object svc : serviceList) {
            if (!first) sb.append(",");
            first = false;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) svc;
            sb.append(JsonUtil.simpleToJson(map));
        }
        sb.append("]}");
        return sb.toString();
    }

}
