package com.logcollect.core.degrade;

import javax.crypto.SecretKey;

public interface DegradeKeyProvider {
    SecretKey resolve();
    int getOrder();
}
