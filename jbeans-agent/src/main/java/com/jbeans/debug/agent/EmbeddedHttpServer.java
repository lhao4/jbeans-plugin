package com.jbeans.debug.agent;

import com.jbeans.debug.agent.handler.*;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jbeans.debug.agent.AgentLogger.log;

/**
 * 嵌入式 HTTP Server – 封装 JDK 内置 HttpServer，管理路由和生命周期。
 */
public final class EmbeddedHttpServer {

    private static volatile HttpServer server;
    private static volatile ExecutorService executor;

    private EmbeddedHttpServer() {
    }

    public static void start(int port) {
        try {
            doStart(port);
        } catch (IOException e) {
            log("Failed to start HTTP Server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void restart(int port) {
        stop();
        // stop 后端口释放需要短暂时间，等待后重试
        for (int i = 0; i < 6; i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            try {
                doStart(port);
                return; // 启动成功
            } catch (IOException e) {
                if (e instanceof java.net.BindException && i < 5) {
                    log("Port " + port + " still in use, retrying... (" + (i + 1) + "/6)");
                    // 尝试发 shutdown 请求关闭残留的旧 Server
                    if (i == 0) {
                        trySendShutdown(port);
                    }
                } else {
                    log("Failed to restart HTTP Server: " + e.getMessage());
                    return;
                }
            }
        }
    }

    public static void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
            server = null;
            log("HTTP Server stopped");
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
            executor = null;
        }
    }

    private static void doStart(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        s.createContext("/jbeans-debug/health", new HealthHandler());
        s.createContext("/jbeans-debug/services", new ServicesHandler());
        s.createContext("/jbeans-debug/invoke", new InvokeHandler());
        s.createContext("/jbeans-debug/schema", new SchemaHandler());
        s.createContext("/jbeans-debug/diag", new DiagHandler());
        s.createContext("/jbeans-debug/bean/resolve", new BeanResolveHandler());

        s.createContext("/jbeans-debug/shutdown", exchange -> {
            byte[] resp = "{\"status\":\"shutdown\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
            // 延迟关闭，让响应先发出去
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                stop();
            }).start();
        });

        ExecutorService newExecutor = createDaemonExecutor();
        s.setExecutor(newExecutor);
        s.start();
        executor = newExecutor;
        server = s;
        log("HTTP Server started on 127.0.0.1:" + port);
    }

    /**
     * 尝试向旧的残留 Server 发 shutdown 请求，强制释放端口。
     */
    private static void trySendShutdown(int port) {
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:" + port + "/jbeans-debug/shutdown");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            try {
                conn.getInputStream().close();
            } catch (Exception ignored) {
            }
            conn.disconnect();
            log("Sent shutdown to old server on port " + port);
        } catch (Exception e) {
            log("Failed to shutdown old server: " + e.getMessage());
        }
    }

    /**
     * 创建 daemon 线程池，确保 Agent 线程不会阻止目标 JVM 正常退出。
     */
    private static ExecutorService createDaemonExecutor() {
        final AtomicInteger counter = new AtomicInteger(0);
        return Executors.newFixedThreadPool(4, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jbeans-debug-http-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }
}
