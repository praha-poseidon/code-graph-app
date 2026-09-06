package com.poseidon.codegraph.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCodecTest {

    @Test
    void encryptsAndDecryptsRepositorySecret() {
        SecretCodec codec = new SecretCodec("test-master-key");

        String encrypted = codec.encrypt("github-token");

        assertThat(encrypted).startsWith("enc:v1:").doesNotContain("github-token");
        assertThat(codec.decrypt(encrypted)).isEqualTo("github-token");
    }

    @Test
    void rejectsSecretWhenMasterKeyIsMissing() {
        SecretCodec codec = new SecretCodec("");

        assertThatThrownBy(() -> codec.encrypt("github-token"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CODEGRAPH_MASTER_KEY");
    }
}
