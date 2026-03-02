package org.springframework.vault.core;

public class VaultTemplate {

    private final Object response;

    public VaultTemplate(Object response) {
        this.response = response;
    }

    public Object read(String path) {
        return response;
    }
}
