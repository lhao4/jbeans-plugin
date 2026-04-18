package com.github.lhao4.jbeans.agent;

import java.lang.instrument.Instrumentation;

public class SpringContextResolver {

    private final Instrumentation instrumentation;

    public SpringContextResolver(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public Object resolve() {
        return null;
    }
}
