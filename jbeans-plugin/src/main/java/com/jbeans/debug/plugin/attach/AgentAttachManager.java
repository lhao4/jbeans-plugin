package com.jbeans.debug.plugin.attach;

import com.intellij.openapi.diagnostic.Logger;
import com.sun.tools.attach.VirtualMachine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 管理 Agent JAR 的提取和注入。
 * <p>
 * Agent JAR 随 Plugin 打包在 resources 中，首次使用时提取到临时目录。
 * 通过 {@link VirtualMachine#loadAgent(String, String)} 注入目标 JVM。
 */
public final class AgentAttachManager {

    private static final Logger LOG = Logger.getInstance(AgentAttachManager.class);

    /**
     * Agent JAR 在 resources 中的路径
     */
    private static final String AGENT_RESOURCE = "/agent/jbeans-agent.jar";

    /**
     * 默认 Agent HTTP 端口
     */
    public static final int DEFAULT_PORT = 12138;

    /**
     * 缓存提取后的 Agent JAR 路径
     */
    private static volatile Path cachedAgentPath;

    private AgentAttachManager() {
    }

    /**
     * 将 Agent 注入目标 JVM 进程。
     *
     * @param pid  目标进程 PID
     * @param port Agent HTTP 端口
     * @throws AgentAttachException 注入失败时抛出
     */
    public static void attach(String pid, int port) throws AgentAttachException {
        Path agentJar = extractAgentJar();
        VirtualMachine vm = null;
        try {
            LOG.info("Attaching agent to PID " + pid + " with port " + port);
            vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentJar.toString(), "port=" + port);
            LOG.info("Agent attached successfully to PID " + pid);
        } catch (com.sun.tools.attach.AgentLoadException e) {
            // AgentLoadException: 0 是已知行为 - Agent 实际已成功加载
            if ("0".equals(e.getMessage())) {
                LOG.info("Agent loaded (AgentLoadException: 0 is expected)");
            } else {
                throw new AgentAttachException("Agent load failed: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new AgentAttachException(
                    "Failed to attach to PID " + pid + ": " + e.getMessage(), e);
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 从 Plugin resources 提取 Agent JAR 到临时目录。
     * 使用双检锁确保只提取一次。
     */
    private static Path extractAgentJar() throws AgentAttachException {
        Path path = cachedAgentPath;
        if (path != null && Files.exists(path)) return path;

        synchronized (AgentAttachManager.class) {
            path = cachedAgentPath;
            if (path != null && Files.exists(path)) return path;

            try (InputStream is = AgentAttachManager.class.getResourceAsStream(AGENT_RESOURCE)) {
                if (is == null) {
                    throw new AgentAttachException(
                            "Agent JAR not found in plugin resources: " + AGENT_RESOURCE);
                }
                Path tempDir = Files.createTempDirectory("jbeans-agent-");
                Path target = tempDir.resolve("jbeans-agent.jar");
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                // 标记 JVM 退出时清理
                target.toFile().deleteOnExit();
                tempDir.toFile().deleteOnExit();
                cachedAgentPath = target;
                LOG.info("Agent JAR extracted to: " + target);
                return target;
            } catch (IOException e) {
                throw new AgentAttachException("Failed to extract agent JAR: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Agent 注入专用异常。
     */
    public static class AgentAttachException extends Exception {
        public AgentAttachException(String message) {
            super(message);
        }

        public AgentAttachException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
