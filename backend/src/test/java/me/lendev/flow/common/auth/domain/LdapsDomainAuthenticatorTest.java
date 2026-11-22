package me.lendev.flow.common.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LdapsDomainAuthenticatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"ldap://directory.example", "ldaps://", "ldaps://user@directory.example",
            "ldaps://directory.example/ou=people", "ldaps://directory.example?query", "ldaps://directory.example#fragment",
            "ldaps://directory.example:65536", "ldaps://["})
    void rejectsUrlsThatCannotBeUsedForAPlainLdapsBind(String url) {
        var authenticator = new LdapsDomainAuthenticator(url);
        assertThat(authenticator.configured()).isFalse();
        assertThatThrownBy(() -> authenticator.authenticate("user@example", "password"))
                .isInstanceOf(DomainUnavailableException.class);
    }

    @Test
    void rejectsEmptyCredentialsBeforeConnecting() {
        var authenticator = new LdapsDomainAuthenticator("ldaps://directory.example:636/");
        assertThat(authenticator.configured()).isTrue();
        assertThat(authenticator.authenticate(null, "password")).isFalse();
        assertThat(authenticator.authenticate("user@example", null)).isFalse();
        assertThat(authenticator.authenticate("user@example", " ")).isFalse();
    }

    @Test
    void interruptedTlsHandshakeIsAServiceFailure() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(5000);
            var rejected = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoLinger(true, 0);
                }
                return null;
            });
            var authenticator = new LdapsDomainAuthenticator("ldaps://127.0.0.1:" + listener.getLocalPort());
            assertThatThrownBy(() -> authenticator.authenticate("user@example", "password"))
                    .isInstanceOf(DomainUnavailableException.class);
            rejected.get(5, TimeUnit.SECONDS);
        }
    }
}
