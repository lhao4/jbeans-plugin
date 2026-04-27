package com.jbeans.debug.agent.handler;

import com.jbeans.debug.agent.JsonUtil;
import com.jbeans.debug.agent.SpringContextLocator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.management.ManagementFactory;

/**
 * GET /jbeans-debug/health - 健康检查端点。
 * 手写 JSON，不依赖 Jackson（必须在 Jackson 不可用时也能工作）。
 */
public class HealthHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Object ctx = SpringContextLocator.getContext();
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        String ctxClassName = ctx != null ? ctx.getClass().getName() : "null";
        String status = ctx != null ? "ok" : "waiting";
        String message = ctx != null
                ? "Spring ApplicationContext acquired"
                : "Waiting for Spring ApplicationContext...";

        String json = "{"
                + "\"status\":\"" + status + "\""
                + ",\"version\":\"1.0.0\""
                + ",\"pid\":\"" + pid + "\""
                + ",\"contextClass\":\"" + JsonUtil.escapeJson(ctxClassName) + "\""
                + ",\"message\":\"" + JsonUtil.escapeJson(message) + "\""
                + "}";

        HandlerUtil.sendJson(exchange, 200, json);
    }
}
