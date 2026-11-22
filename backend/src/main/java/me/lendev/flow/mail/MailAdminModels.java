package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.MAX_BODY;
import static me.lendev.flow.mail.MailModels.MAX_SUBJECT;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

import me.lendev.flow.mail.MailModels.Credentials;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.SendMode;
import me.lendev.flow.mail.MailModels.Settings;
import me.lendev.flow.mail.MailStore.SendDetail;
import me.lendev.flow.mail.MailStore.SendLog;

/**
 * 邮件配置、模板和发送日志的管理接口契约。
 */
public final class MailAdminModels {

    private static final String CODE = "[A-Za-z0-9][A-Za-z0-9_.-]{0,63}";

    public record AccountRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 100) String name,
                                 @NotNull Provider provider, @NotBlank @Email @Size(max = 254) String mailboxAddress,
                                 @NotNull Settings config, Credentials credentials, @NotNull Boolean enable) {
        public AccountRequest {
            code = code == null ? null : code.strip();
        }
    }

    public record AccountView(long id, String code, String name, Provider provider, String mailboxAddress,
                              Settings config, boolean credentialConfigured, boolean enable, long createdBy,
                              Instant createdAt,
                              long updatedBy, Instant updatedAt) {
    }

    public record TemplateRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 100) String name,
                                  @NotNull @Positive Long accountId,
                                  @NotBlank @Size(max = MAX_SUBJECT) String subjectTemplate,
                                  @NotBlank @Size(max = MAX_BODY) String bodyTemplate, SendMode sendMode,
                                  @NotNull Boolean enable) {
        public TemplateRequest {
            code = code == null ? null : code.strip();
        }
    }

    public record TemplateView(long id, String code, String name, long accountId, String subjectTemplate,
                               String bodyTemplate, String bodyFormat, SendMode sendMode, int revision, boolean enable,
                               long createdBy, Instant createdAt, long updatedBy, Instant updatedAt) {
    }

    public record Page<T>(List<T> records, long total) {
    }

    public record Option(long id, String code, String name, boolean enable) {
    }

    public record LogOptions(List<Option> accounts, List<Option> templates) {
    }

    public record LogView(@JsonUnwrapped SendLog log, List<SendDetail> details) {
    }

    private MailAdminModels() {
    }
}
