package com.jbeans.debug.agent;

/**
 * Agent 统一日志输出
 */
public final class AgentLogger {
    public static final String PREFIX = "[JbeansDebugAgent]";

    private AgentLogger() {}

    public static void log(String message) {
        System.out.println(PREFIX + message);
    }

    public static void log(String message, Throwable throwable) {
        System.out.println(PREFIX + message + " - " + throwable);
    }
}
