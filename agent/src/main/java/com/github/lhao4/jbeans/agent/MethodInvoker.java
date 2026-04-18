package com.github.lhao4.jbeans.agent;

public class MethodInvoker {

    private final Object applicationContext;

    public MethodInvoker(Object applicationContext) {
        this.applicationContext = applicationContext;
    }

    public String invoke(String beanName, String methodName, String[] paramTypes, String argsJson) {
        return null;
    }
}
