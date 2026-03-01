package com.logcollect.core.degrade;

public interface KmsKeyProvider {
    byte[] getKey(String keyAlias);
}
