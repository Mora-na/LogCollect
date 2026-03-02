package com.logcollect.core.degrade;

public class TestKmsKeyProvider implements KmsKeyProvider {

    static volatile byte[] key;
    static volatile boolean throwError;

    @Override
    public byte[] getKey(String keyAlias) {
        if (throwError) {
            throw new RuntimeException("kms provider failure");
        }
        return key;
    }

    static void reset() {
        key = null;
        throwError = false;
    }
}
