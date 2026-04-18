package com.github.lhao4.jbeans.agent;

import java.lang.instrument.Instrumentation;

public class JBeansAgent {

    public static void agentmain(String args, Instrumentation inst) {
        int port = 0;
        if (args != null) {
            for (String part : args.split(",")) {
                if (part.startsWith("port=")) {
                    port = Integer.parseInt(part.substring(5).trim());
                }
            }
        }
        if (port <= 0) return;

        final int finalPort = port;
        Thread t = new Thread(() -> new AgentServer(finalPort).run(), "jbeans-agent-server");
        t.setDaemon(true);
        t.start();
    }
}
