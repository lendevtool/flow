package me.lendev.flow.common.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class MfaSecretsTest {

    private final MfaKeyWrapping wrapping = new MfaKeyWrapping(MfaSecrets.randomDataKey());
    private final MfaKeyStore keys = mock(MfaKeyStore.class);
    private final MfaSecrets secrets = new MfaSecrets(mock(MfaRotationStore.class), keys, wrapping);

    @Test
    void rejectsCorruptOrMisboundKeysAndSeeds() {
        var key = MfaSecrets.decodeKey(MfaSecrets.randomDataKey());
        var wrapped = wrapping.wrap("v1", key);
        when(keys.publishedSecret("v1")).thenReturn(wrapped);
        when(keys.publishedSecret("v2")).thenReturn(wrapping.wrap("v2", key));
        var seed = secrets.newSecret();
        var ciphertext = secrets.protect(42, "v1", seed);
        assertThat(secrets.open(42, ciphertext)).isEqualTo(seed);
        assertThatThrownBy(() -> secrets.open(43, ciphertext)).isInstanceOf(MfaUnavailableException.class);
        var wrongVersion = "v2" + ciphertext.substring(2);
        assertThatThrownBy(() -> secrets.open(42, wrongVersion)).isInstanceOf(MfaUnavailableException.class);
        when(keys.publishedSecret("v1")).thenReturn(null);
        assertThatThrownBy(() -> secrets.open(42, ciphertext)).isInstanceOf(MfaUnavailableException.class);

        assertThatThrownBy(() -> wrapping.unwrap("v2", wrapped)).isInstanceOf(MfaUnavailableException.class);
        var shortKey = wrapping.wrap("v1", new SecretKeySpec(new byte[16], "AES"));
        assertThatThrownBy(() -> wrapping.unwrap("v1", shortKey)).isInstanceOf(MfaUnavailableException.class);
        var unconfigured = new MfaKeyWrapping("");
        assertThatThrownBy(() -> unconfigured.unwrap("v1", wrapped)).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> new MfaKeyWrapping("!")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mapsMalformedBase64AndTruncatedPayloadsToUnavailable() {
        String shortPayload = Base64.getEncoder().encodeToString(new byte[12]);
        String shortSeed = "v1:" + shortPayload;
        assertThatThrownBy(() -> wrapping.unwrap("v1", "!")).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> wrapping.unwrap("v1", shortPayload)).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> secrets.open(42, "v1:!")).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> secrets.open(42, shortSeed)).isInstanceOf(MfaUnavailableException.class);
    }
}
