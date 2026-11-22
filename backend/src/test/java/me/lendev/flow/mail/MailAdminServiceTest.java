package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.util.Base64;
import java.util.stream.Stream;

import me.lendev.flow.mail.MailAdminModels.AccountRequest;
import me.lendev.flow.mail.persistence.MailAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class MailAdminServiceTest {

    private static final long ACCOUNT_ID = 42L;
    private static final Endpoint IMAP = new Endpoint("imap.example.com", 993, "reader", Tls.SSL);
    private static final Endpoint SMTP = new Endpoint("smtp.example.com", 587, "sender", Tls.STARTTLS);
    private static final Settings IMAP_SMTP_SETTINGS = new Settings(IMAP, SMTP, null);
    private static final Credentials IMAP_SMTP_CREDENTIALS = new Credentials("old-reader-password", "old-sender-password", null);
    private static final String CLIENT_ID = "f73a7afd-bf20-4e22-9b0c-879ccf90bb73";
    private static final Settings GRAPH_SETTINGS = new Settings(null, null,
            new GraphSettings("example.onmicrosoft.com", CLIENT_ID, "mail@example.com"));
    private static final Credentials GRAPH_CREDENTIALS = new Credentials(null, null, "original-client-secret");

    private final JsonMapper json = JsonMapper.builder().build();
    private final MailCredentialsCipher cipher = new MailCredentialsCipher(json, Base64.getEncoder().encodeToString(new byte[32]));
    private final MailAccountConfiguration configuration = new MailAccountConfiguration(cipher, json);
    private final MailStore store = mock(MailStore.class);
    private final MailAdminService service = new MailAdminService(store, new MailTemplateRenderer(), configuration, Clock.systemUTC());

    @ParameterizedTest
    @MethodSource("changedEndpoints")
    void imapConnectionChangesRequireItsPassword(Endpoint updatedEndpoint) {
        var row = existing(Provider.IMAP_SMTP, IMAP_SMTP_SETTINGS, IMAP_SMTP_CREDENTIALS);
        var updatedSettings = new Settings(updatedEndpoint, SMTP, null);
        var request = request(Provider.IMAP_SMTP, updatedSettings, new Credentials(" ", "new-sender-password", null));

        requireCredentials(request);
        assertEquals(IMAP_SMTP_SETTINGS, configuration.connection(row).settings());
    }

    private static Stream<Endpoint> changedEndpoints() {
        return Stream.of(new Endpoint("imap-new.example.com", 993, "reader", Tls.SSL),
                new Endpoint("imap.example.com", 1993, "reader", Tls.SSL),
                new Endpoint("imap.example.com", 993, "Reader", Tls.SSL),
                new Endpoint("imap.example.com", 993, "reader", Tls.STARTTLS));
    }

    @Test
    void smtpConnectionChangeRequiresItsPasswordAndPreservesImapWithSuppliedSmtpPassword() {
        var row = existing(Provider.IMAP_SMTP, IMAP_SMTP_SETTINGS, IMAP_SMTP_CREDENTIALS);
        var updatedSettings = new Settings(IMAP,
                new Endpoint("smtp-new.example.com", 465, "sender", Tls.SSL), null);

        requireCredentials(request(Provider.IMAP_SMTP, updatedSettings, null));

        var updated = service.updateAccount(ACCOUNT_ID,
                request(Provider.IMAP_SMTP, updatedSettings, new Credentials(null, "new-sender-password", null)), 1L);
        assertEquals(updatedSettings, updated.config());
        assertEquals(new Credentials("old-reader-password", "new-sender-password", null), configuration.connection(row).credentials());
    }

    @Test
    void dnsHostCapitalizationAndAccountMetadataChangesPreservePasswords() {
        var row = existing(Provider.IMAP_SMTP, IMAP_SMTP_SETTINGS, IMAP_SMTP_CREDENTIALS);
        String originalCiphertext = row.getCredentialCiphertext();
        var updatedSettings = new Settings(new Endpoint("IMAP.example.com", 993, "reader", Tls.SSL), SMTP, null);

        var updated = service.updateAccount(ACCOUNT_ID, request(Provider.IMAP_SMTP, updatedSettings, null), 1L);

        assertEquals("renamed@example.com", updated.mailboxAddress());
        assertEquals(originalCiphertext, row.getCredentialCiphertext());
    }

    @ParameterizedTest
    @MethodSource("changedGraphIdentity")
    void graphApplicationIdentityChangeRequiresAClientSecret(GraphSettings graph) {
        var row = existing(Provider.GRAPH, GRAPH_SETTINGS, GRAPH_CREDENTIALS);
        var updatedSettings = new Settings(null, null, graph);

        requireCredentials(request(Provider.GRAPH, updatedSettings, null));

        var updated = service.updateAccount(ACCOUNT_ID,
                request(Provider.GRAPH, updatedSettings, new Credentials(null, null, "replacement-client-secret")), 1L);
        assertEquals(updatedSettings, updated.config());
        assertEquals("replacement-client-secret", configuration.connection(row).credentials().clientSecret());
    }

    private static Stream<GraphSettings> changedGraphIdentity() {
        return Stream.of(new GraphSettings("updated.onmicrosoft.com", CLIENT_ID),
                new GraphSettings("example.onmicrosoft.com", "9814acdc-221b-40be-8c87-e2b2228265b7"));
    }

    @Test
    void graphMailboxChangePreservesClientSecretForTheSameApplicationIdentity() {
        var row = existing(Provider.GRAPH, GRAPH_SETTINGS, GRAPH_CREDENTIALS);
        row.setConfigJson("""
                {"graph":{"tenantId":"example.onmicrosoft.com","clientId":"f73a7afd-bf20-4e22-9b0c-879ccf90bb73"}}
                """);
        String originalCiphertext = row.getCredentialCiphertext();
        var updatedSettings = new Settings(null, null, new GraphSettings("EXAMPLE.onmicrosoft.com",
                "F73A7AFD-BF20-4E22-9B0C-879CCF90BB73", "other@example.com"));

        var updated = service.updateAccount(ACCOUNT_ID, request(Provider.GRAPH, updatedSettings, null), 1L);

        assertEquals(updatedSettings, updated.config());
        assertEquals(originalCiphertext, row.getCredentialCiphertext());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unreadable settings", "null"})
    void unreadableStoredSettingsNeedExplicitCredentialsToRepair(String invalidConfig) {
        var row = existing(Provider.IMAP_SMTP, IMAP_SMTP_SETTINGS, IMAP_SMTP_CREDENTIALS);
        row.setConfigJson(invalidConfig);
        var request = request(Provider.IMAP_SMTP, IMAP_SMTP_SETTINGS, null);

        var failure = assertThrows(MailOperationException.class, () -> service.updateAccount(ACCOUNT_ID, request, 1L));
        assertEquals("MAIL_CONFIG_UNAVAILABLE", failure.code());
        assertEquals(invalidConfig, row.getConfigJson());

        var replacements = new Credentials("replacement-reader-password", "replacement-sender-password", null);
        var updated = service.updateAccount(ACCOUNT_ID, request(Provider.IMAP_SMTP, IMAP_SMTP_SETTINGS, replacements), 1L);
        assertEquals(IMAP_SMTP_SETTINGS, updated.config());
        assertEquals(replacements, configuration.connection(row).credentials());
    }

    private MailAccount existing(Provider provider, Settings settings, Credentials credentials) {
        var row = new MailAccount();
        row.setId(ACCOUNT_ID);
        row.setCode("EXAMPLE");
        row.setName("Example");
        row.setProvider(provider);
        row.setMailboxAddress("mail@example.com");
        row.setConfigJson(json.writeValueAsString(settings));
        row.setCredentialCiphertext(cipher.seal(provider, credentials));
        row.setEnable(true);
        row.setCreatedBy(1L);
        when(store.account(ACCOUNT_ID, true)).thenReturn(row);
        return row;
    }

    private static AccountRequest request(Provider provider, Settings settings, Credentials credentials) {
        return new AccountRequest("EXAMPLE", "Renamed", provider, "renamed@example.com", settings, credentials, true);
    }

    private void requireCredentials(AccountRequest request) {
        var failure = assertThrows(MailOperationException.class, () -> service.updateAccount(ACCOUNT_ID, request, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, failure.status());
        assertEquals("MAIL_CREDENTIAL_REQUIRED", failure.code());
        verify(store, never()).saveAccount(any());
    }
}
