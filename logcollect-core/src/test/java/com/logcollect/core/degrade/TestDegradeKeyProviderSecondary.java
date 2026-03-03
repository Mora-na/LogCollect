package com.logcollect.core.degrade;

import javax.crypto.SecretKey;

public class TestDegradeKeyProviderSecondary implements DegradeKeyProvider {

    static volatile SecretKey key;
    static volatile int order = 10;

    @Override
    public SecretKey resolve() {
        return key;
    }

    @Override
    public int getOrder() {
        return order;
    }

    static void reset() {
        key = null;
        order = 10;
    }
}
