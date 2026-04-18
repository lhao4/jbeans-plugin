package com.github.lhao4.jbeans.agent;

import java.io.*;
import java.net.*;

class AgentServer implements Runnable {

    private final int port;

    AgentServer(int port) {
        this.port = port;
    }

    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(port)) {
            server.setSoTimeout(1000);
            while (true) {
                try {
                    Socket client = server.accept();
                    new Thread(() -> handle(client), "jbeans-agent-req").start();
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            System.err.println("[JBeansAgent] Server error: " + e.getMessage());
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read request line
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String fullPath = parts[1];

            String path = fullPath;
            String query = "";
            int q = fullPath.indexOf('?');
            if (q >= 0) {
                path = fullPath.substring(0, q);
                query = fullPath.substring(q + 1);
            }

            // Read headers
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }

            // Read body
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(bodyBytes, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyBytes, "UTF-8");

            String response;
            if ("GET".equals(method) && "/ping".equals(path)) {
                response = "ok";
            } else if ("POST".equals(method) && "/invoke".equals(path)) {
                response = InvokeHandler.handle(query, body);
            } else {
                sendResponse(out, 404, "Not Found");
                return;
            }
            sendResponse(out, 200, response);
        } catch (Exception e) {
            try {
                OutputStream out = socket.getOutputStream();
                sendResponse(out, 500, "{\"success\":false,\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception ignored) {}
        }
    }

    private void sendResponse(OutputStream out, int code, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + code + " OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    static String escape(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
