package com.jbeans.debug.agent.handler;

import com.jbeans.debug.agent.JsonUtil;
import com.jbeans.debug.agent.SpringContextLocator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 通过反射分析类的字段结构，返回带默认值的 JSON 示例。
 * Schema 生成逻辑委托给 {@link SchemaUtil}。
 */
public class SchemaHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String typeName = null;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("type=")) {
                    typeName = param.substring(5);
                    break;
                }
            }
        }

        if (typeName == null || typeName.isEmpty()) {
            HandlerUtil.sendJson(exchange, 400,
                    "{\"error\":\"Missing 'type' query parameter\"}");
            return;
        }

        Object ctx = SpringContextLocator.getContext();
        ClassLoader appCl = ctx != null
                ? ctx.getClass().getClassLoader()
                : SpringContextLocator.findAppClassLoader();

        try {
            Class<?> clazz = Class.forName(typeName, true, appCl);
            Set<String> visiting = new HashSet<>();
            Object defaultValue = SchemaUtil.buildDefaultValue(clazz, clazz, visiting, 0);

            JsonUtil jsonUtil = new JsonUtil(appCl);
            String json;
            if (jsonUtil.isAvailable()) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("type", typeName);
                resp.put("schema", defaultValue);
                json = jsonUtil.toJson(resp);
            } else {
                json = "{\"type\":\"" + JsonUtil.escapeJson(typeName) + "\""
                        + ",\"schema\":" + String.valueOf(defaultValue) + "}";
            }

            HandlerUtil.sendJson(exchange, 200, json);

        } catch (ClassNotFoundException e) {
            HandlerUtil.sendJson(exchange, 404,
                    "{\"error\":\"Class not found: " + JsonUtil.escapeJson(typeName) + "\"}");
        } catch (Exception e) {
            HandlerUtil.sendJson(exchange, 500,
                    "{\"error\":\"" + JsonUtil.escapeJson(e.getMessage()) + "\"}");
        }
    }
}
