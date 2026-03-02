package com.logcollect.core.degrade;

import javax.crypto.SecretKey;

public class TestDegradeKeyProvider implements DegradeKeyProvider {

    static volatile SecretKey key;
    static volatile boolean throwError;
    static volatile int order = 0;

    @Override
    public SecretKey resolve() {
        if (throwError) {
            throw new RuntimeException("provider error");
        }
        return key;
    }

    @Override
    public int getOrder() {
        return order;
    }

    static void reset() {
        key = null;
        throwError = false;
        order = 0;
    }
}
