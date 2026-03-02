package com.logcollect.core.degrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DegradeStorageExceptionTest {

    @Test
    void constructor_keepsCause() {
        RuntimeException cause = new RuntimeException("boom");
        DegradeStorageException exception = new DegradeStorageException(cause);
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
