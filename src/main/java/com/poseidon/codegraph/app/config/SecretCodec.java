package com.poseidon.codegraph.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class SecretCodec {

    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String masterKey;

    public SecretCodec(@Value("${code-graph.security.master-key:}") String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey.trim();
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (masterKey.isBlank()) {
            throw new IllegalStateException("保存仓库凭证前必须配置 CODEGRAPH_MASTER_KEY");
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("仓库凭证加密失败", exception);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.startsWith(PREFIX)) {
            throw new IllegalStateException("仓库凭证格式不受支持");
        }
        if (masterKey.isBlank()) {
            throw new IllegalStateException("读取仓库凭证前必须配置 CODEGRAPH_MASTER_KEY");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] nonce = new byte[12];
            byte[] encrypted = new byte[payload.length - nonce.length];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("仓库凭证解密失败，请检查 CODEGRAPH_MASTER_KEY", exception);
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
