package me.lendev.flow.mail;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.mail.MailAdminModels.AccountRequest;
import me.lendev.flow.mail.MailModels.Connection;
import me.lendev.flow.mail.MailModels.Credentials;
import me.lendev.flow.mail.MailModels.Endpoint;
import me.lendev.flow.mail.MailModels.GraphSettings;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Settings;
import me.lendev.flow.mail.persistence.MailAccount;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 校验和转换邮箱连接配置，合并并加密更新的凭据。
 */
@Component
@RequiredArgsConstructor
final class MailAccountConfiguration {

    private static final int MAX_SECRET_LENGTH = 2048;
    private static final String CREDENTIAL_REQUIRED = "MAIL_CREDENTIAL_REQUIRED";
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern TENANT = Pattern.compile("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");

    private final MailCredentialsCipher cipher;
    private final ObjectMapper json;

    static Settings validateSettings(AccountRequest request) {
        var config = request.config();
        if (request.provider() == Provider.GRAPH) {
            var graph = config.graph();
            if (graph == null || graph.tenantId() == null || graph.clientId() == null
                    || !TENANT.matcher(graph.tenantId().strip()).matches()
                    || !UUID.matcher(graph.clientId().strip()).matches()) {
                throw invalid("MAIL_GRAPH_SETTINGS_INVALID", "请输入有效的 Graph 租户和应用客户端 ID");
            }
            return new Settings(null, null, new GraphSettings(graph.tenantId().strip(), graph.clientId().strip(),
                    mailboxId(graph.mailboxId())));
        }
        return new Settings(endpoint(config.imap(), "IMAP"), endpoint(config.smtp(), "SMTP"), null);
    }

    String writeSettings(Settings settings) {
        return json.writeValueAsString(settings);
    }

    Settings readSettings(String configJson) {
        Settings settings;
        try {
            settings = configJson == null ? null : json.readValue(configJson, Settings.class);
        } catch (JacksonException invalid) {
            throw unavailableSettings();
        }
        if (settings == null) {
            throw unavailableSettings();
        }
        return settings;
    }

    Connection connection(MailAccount account) {
        return new Connection(account.getProvider(), account.getMailboxAddress(), readSettings(account.getConfigJson()),
                cipher.open(account.getProvider(), account.getCredentialCiphertext()));
    }

    /**
     * 认证目标相同时允许省略对应凭据，仅在部分更新凭据时解密原值。
     */
    String nextCiphertext(MailAccount account, Provider provider, Settings settings, Credentials credentials) {
        if (provider == Provider.GRAPH) {
            return nextGraphCiphertext(account, settings.graph(), credentials);
        }
        String existing = account.getCredentialCiphertext();
        String imap = credentials == null ? null : supplied(credentials.imapPassword());
        String smtp = credentials == null ? null : supplied(credentials.smtpPassword());
        if (existing != null && (imap == null || smtp == null)) {
            var previous = readSettings(account.getConfigJson());
            requireUnchangedEndpoint(imap, previous.imap(), settings.imap(), "IMAP");
            requireUnchangedEndpoint(smtp, previous.smtp(), settings.smtp(), "SMTP");
            if (imap == null && smtp == null) {
                return existing;
            }
            var saved = cipher.open(provider, existing);
            imap = imap == null ? saved.imapPassword() : imap;
            smtp = smtp == null ? saved.smtpPassword() : smtp;
        }
        return cipher.seal(provider, new Credentials(required(imap), required(smtp), null));
    }

    private String nextGraphCiphertext(MailAccount account, GraphSettings graph, Credentials credentials) {
        String existing = account.getCredentialCiphertext();
        String secret = credentials == null ? null : supplied(credentials.clientSecret());
        if (secret == null && existing != null) {
            var previous = readSettings(account.getConfigJson()).graph();
            if (previous == null || !graph.tenantId().equalsIgnoreCase(previous.tenantId())
                    || !graph.clientId().equalsIgnoreCase(previous.clientId())
                    || !TENANT.matcher(previous.tenantId()).matches() || !UUID.matcher(previous.clientId()).matches()) {
                throw invalid(CREDENTIAL_REQUIRED, "Graph 租户或应用配置已变更，请填写客户端密钥");
            }
            return existing;
        }
        return cipher.seal(Provider.GRAPH, new Credentials(null, null, required(secret)));
    }

    private static void requireUnchangedEndpoint(String password, Endpoint previous, Endpoint next, String protocol) {
        if (password == null && (previous == null || !sameHost(previous.host(), next.host())
                || previous.port() != next.port() || previous.tls() != next.tls()
                || !next.username().equals(previous.username()))) {
            throw invalid(CREDENTIAL_REQUIRED, protocol + " 连接配置已变更，请填写对应密码");
        }
    }

    private static boolean sameHost(String previous, String next) {
        // IPv6 区域标识可能区分大小写。
        return next.equals(previous) || (next.indexOf('%') < 0 && next.equalsIgnoreCase(previous) && validHost(previous));
    }

    private static String mailboxId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String id = value.strip();
        if (UUID.matcher(id).matches()) {
            return id;
        }
        int separator = id.indexOf('@');
        if (id.length() > 254 || separator < 1 || id.indexOf('@', separator + 1) != -1
                || !id.substring(0, separator).matches("[A-Za-z0-9._+#!'%-]+") || !validHost(id.substring(separator + 1))) {
            throw invalid("MAIL_GRAPH_MAILBOX_ID_INVALID", "Graph 邮箱标识必须是用户对象 ID 或 UPN");
        }
        return id;
    }

    private static Endpoint endpoint(Endpoint value, String protocol) {
        if (value == null || value.host() == null || !validHost(value.host().strip()) || value.port() < 1
                || value.port() > 65535 || value.tls() == null || value.username() == null
                || value.username().isBlank() || value.username().length() > 254 || hasControl(value.username())) {
            throw invalid("MAIL_ENDPOINT_INVALID", "请输入有效的 " + protocol + " 服务器、端口、TLS 和登录账号");
        }
        return new Endpoint(value.host().strip(), value.port(), value.username().strip(), value.tls());
    }

    private static boolean validHost(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        try {
            String parsed = new URI("mail", null, host, -1, null, null, null).getHost();
            return parsed != null && (parsed.equalsIgnoreCase(host) || parsed.equalsIgnoreCase("[" + host + "]"));
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static String supplied(String value) {
        if (value != null && value.length() > MAX_SECRET_LENGTH) {
            throw invalid("MAIL_CREDENTIAL_INVALID", "邮箱凭据超过长度限制");
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw invalid(CREDENTIAL_REQUIRED, "请填写邮箱凭据");
        }
        return value;
    }

    private static MailOperationException invalid(String code, String message) {
        return new MailOperationException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static MailOperationException unavailableSettings() {
        return new MailOperationException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_CONFIG_UNAVAILABLE", "邮箱连接配置不可用");
    }
}
