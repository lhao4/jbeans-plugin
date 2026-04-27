package com.jbeans.debug.plugin.attach;

import com.intellij.openapi.diagnostic.Logger;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 发现本地 JVM 进程，过滤出可能的 Spring Boot / 服务应用。
 * <p>
 * 使用 {@code com.sun.tools.attach.VirtualMachine.list()} 枚举。
 * IDEA 2022.3+ 自带 JDK 17+，tools.jar 已内置，无需额外依赖。
 */
public final class ProcessDiscovery {

    private static final Logger LOG = Logger.getInstance(ProcessDiscovery.class);

    /** 不可实例化 */
    private ProcessDiscovery() {}

    /**
     * 进程信息。
     */
    public static final class JvmProcessInfo {
        private final String pid;
        private final String displayName;
        private final String mainClass;

        public JvmProcessInfo(String pid, String displayName, String mainClass) {
            this.pid = pid;
            this.displayName = displayName;
            this.mainClass = mainClass;
        }

        public String getPid() { return pid; }
        public String getDisplayName() { return displayName; }
        public String getMainClass() { return mainClass; }

        /** 下拉框展示文本: "PID - MainClassName" */
        @Override
        public String toString() {
            return pid + " - " + displayName;
        }
    }

    /**
     * 列出本地 Java 进程，过滤掉 IDE 自身和工具进程。
     *
     * @return 按 PID 降序排列（最新进程在前）
     */
    public static List<JvmProcessInfo> listJavaProcesses() {
        List<JvmProcessInfo> result = new ArrayList<>();
        try {
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            String selfPid = String.valueOf(ProcessHandle.current().pid());

            for (VirtualMachineDescriptor vmd : vms) {
                String pid = vmd.id();
                String rawName = vmd.displayName();

                // 跳过自身
                if (pid.equals(selfPid)) continue;

                // 跳过 IDE 进程和常见工具
                if (isToolProcess(rawName)) continue;

                String mainClass = extractMainClass(rawName);
                String displayName = mainClass.isEmpty() ? "(unknown)" : mainClass;

                result.add(new JvmProcessInfo(pid, displayName, mainClass));
            }
        } catch (Exception e) {
            LOG.warn("Failed to list JVM processes", e);
        }

        // 按 PID 降序（最新启动的在前）
        result.sort(Comparator.comparingLong((JvmProcessInfo p) -> {
            try { return Long.parseLong(p.pid); } catch (NumberFormatException ex) { return 0L; }
        }).reversed());

        return result;
    }

    /**
     * 判断是否为工具/IDE 进程（应被过滤）。
     */
    private static boolean isToolProcess(String displayName) {
        if (displayName == null || displayName.isEmpty()) return false;
        String lower = displayName.toLowerCase();
        return lower.contains("intellij")
                || lower.contains("com.intellij.")
                || lower.contains("com.jetbrains.")
                || lower.contains("org.jetbrains.")
                || lower.contains("jps")
                || lower.contains("jcmd")
                || lower.contains("jconsole")
                || lower.contains("jvisualvm")
                || lower.contains("visualvm")
                || lower.contains("gradle")
                || lower.contains("maven")
                || lower.contains("kotlin.daemon")
                || lower.contains("kotlinlsp")
                || lower.contains("sonarlint")
                || lower.contains("sonarqube")
                || lower.contains("org.eclipse.")
                || lower.contains("jboss.modules")
                || lower.contains("wrapper.jar");
    }

    /**
     * 从 displayName 提取主类简名。
     * <p>
     * displayName 格式可能是:
     * <ul>
     * <li>完全限定类名 "com.example.MyApp"</li>
     * <li>jar 路径 "/path/to/app.jar"</li>
     * <li>带参数 "com.example.MyApp --server.port=8080"</li>
     * <li>空字符串</li>
     * </ul>
     */
    private static String extractMainClass(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";

        // 去掉命令行参数
        String name = displayName.split("\\s+")[0];

        // jar 路径: 提取文件名
        if (name.endsWith(".jar")) {
            int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            return sep >= 0 ? name.substring(sep + 1) : name;
        }

        // 完全限定类名: 取最后一段
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1);
        }

        return name;
    }

}
