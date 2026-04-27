package com.jbeans.debug.plugin.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JBeans Debug 客户端，与目标进程中的 embedded HTTP Server 通信。
 */
public class JbeansDebugClient {

    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .disableHtmlEscaping()
            .create();

    private static final int CONNECT_TIMEOUT = 3000;
    /**
     * 通用读超时（健康检查、服务发现、schema、bean resolve 等非业务接口）
     */
    private static final int DEFAULT_READ_TIMEOUT = 10_000;
    /**
     * 方法调用读超时 - 无限等待，兼容 Debug 断点暂停场景
     */
    private static final int INVOKE_READ_TIMEOUT = 0;

    public static boolean healthCheck(String host, int port) {
        try {
            String url = buildUrl(host, port, "/jbeans-debug/health");
            String response = doGet(url);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
            return "ok".equals(json.get("status").getAsString());
        } catch (Exception e) {
            return false;
        }
    }

    public static List<Map<String, Object>> listServices(String host, int port) {
        try {
            String url = buildUrl(host, port, "/jbeans-debug/services");
            String response = doGet(url);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
            Type listType = new TypeToken<List<Map<String, Object>>>() {
            }.getType();
            return GSON.fromJson(json.get("services"), listType);
        } catch (IOException e) {
            throw new JbeansDebugException("CONNECTION_ERROR",
                    "Failed to connect to SDK: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> invoke(String host, int port,
                                             String interfaceName, String methodName,
                                             List<String> parameterTypes, List<Object> args) {

        try {
            String url = buildUrl(host, port, "/jbeans-debug/invoke");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("interfaceName", interfaceName);
            params.put("methodName", methodName);
            params.put("parameterTypes", parameterTypes);
            params.put("args", args);
            String jsonBody = GSON.toJson(params);

            String response = doPost(url, jsonBody, INVOKE_READ_TIMEOUT);

            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            return GSON.fromJson(response, mapType);
        } catch (IOException e) {
            throw new JbeansDebugException("CONNECTION_ERROR",
                    "Failed to invoke method: " + e.getMessage(), e);
        }
    }

    public static Object getSchema(String host, int port, String typeName) {
        try {
            String url = buildUrl(host, port, "/jbeans-debug/schema?type="
                    + URLEncoder.encode(typeName, StandardCharsets.UTF_8));
            String response = doGet(url);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
            if (json.has("schema")) {
                Type mapType = new TypeToken<Object>() {
                }.getType();
                return GSON.fromJson(json.get("schema"), mapType);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve a Bean method: find its overloads, parameter types/names/schemas.
     * POST /jbeans-debug/bean/resolve
     *
     * @return parsed response map with className, beanClass, methodName, overloads
     */
    public static Map<String, Object> resolveBeanMethod(String host, int port,
                                                        String className, String methodName) {
        try {
            String url = buildUrl(host, port, "/jbeans-debug/bean/resolve");
            Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("className", className);
            requestBody.put("methodName", methodName);
            String jsonBody = GSON.toJson(requestBody);
            String response = doPost(url, jsonBody);
            Type mapType = new TypeToken<Map<String, Object>>() {
            }.getType();
            return GSON.fromJson(response, mapType);
        } catch (IOException e) {
            throw new JbeansDebugException("CONNECTION_ERROR",
                    "Failed to resolve bean method: " + e.getMessage(), e);
        }
    }

    // ---------------- HTTP helpers ----------------

    private static String doGet(String urlStr) throws IOException {
        return doGet(urlStr, DEFAULT_READ_TIMEOUT);
    }

    private static String doGet(String urlStr, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(readTimeout);
        try {
            return readResponseUtf8(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String doPost(String urlStr, String jsonBody) throws IOException {
        return doPost(urlStr, jsonBody, DEFAULT_READ_TIMEOUT);
    }

    private static String doPost(String urlStr, String jsonBody, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(readTimeout);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return readResponseUtf8(conn);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Read response body with explicit UTF-8, handling both success and error streams.
     * On non-2xx responses, reads error stream to capture SDK error details.
     */
    private static String readResponseUtf8(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        if (stream == null) {
            throw new IOException("HTTP " + code + " with no response body");
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(4096);
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            return sb.toString();
        }
    }

    private static String buildUrl(String host, int port, String path) {
        return "http://" + host + ":" + port + path;
    }
}
