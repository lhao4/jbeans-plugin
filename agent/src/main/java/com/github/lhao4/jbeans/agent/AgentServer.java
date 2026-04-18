package com.github.lhao4.jbeans.agent;

import java.lang.instrument.Instrumentation;

public class AgentServer {

    private final int port;
    private final Instrumentation instrumentation;

    public AgentServer(int port, Instrumentation instrumentation) {
        this.port = port;
        this.instrumentation = instrumentation;
    }

    public void start() {
    }
}
