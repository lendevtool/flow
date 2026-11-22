package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailAdminService.requireAccount;
import static me.lendev.flow.mail.MailAdminService.requireTemplate;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.mail.MailAdminModels.AccountRequest;
import me.lendev.flow.mail.MailAdminModels.AccountView;
import me.lendev.flow.mail.MailAdminModels.TemplateRequest;
import me.lendev.flow.mail.MailAdminModels.TemplateView;
import me.lendev.flow.mail.MailModels.Settings;
import me.lendev.flow.mail.persistence.MailAccount;
import me.lendev.flow.mail.persistence.MailTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 邮件管理审计只保存普通配置与模板元数据，记录凭据是否变更以及模板版本。
 */
@Component
@RequiredArgsConstructor
public class MailAuditHandler implements AuditHandler {

    private final MailStore store;
    private final AuditSnapshots snapshots;
    private final ObjectMapper json;

    @Override
    public Set<AuditOperation> operations() {
        return EnumSet.of(AuditOperation.MAIL_ACCOUNT_CREATE, AuditOperation.MAIL_ACCOUNT_UPDATE,
                AuditOperation.MAIL_TEMPLATE_CREATE, AuditOperation.MAIL_TEMPLATE_UPDATE);
    }

    @Override
    public void validate(Method method) {
        AuditOperation operation = method.getAnnotation(Audited.class).value();
        boolean account = operation == AuditOperation.MAIL_ACCOUNT_CREATE || operation == AuditOperation.MAIL_ACCOUNT_UPDATE;
        boolean create = operation == AuditOperation.MAIL_ACCOUNT_CREATE || operation == AuditOperation.MAIL_TEMPLATE_CREATE;
        Class<?> request = account ? AccountRequest.class : TemplateRequest.class;
        Class<?>[] expected = create ? new Class<?>[]{request, long.class} : new Class<?>[]{Long.class, request, long.class};
        if (!Arrays.equals(expected, method.getParameterTypes())
                || method.getReturnType() != (account ? AccountView.class : TemplateView.class)) {
            throw new IllegalStateException("邮件审计方法签名与注册定义不一致: " + method.getName());
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        return switch (operation) {
            case MAIL_ACCOUNT_CREATE -> result -> List.of(snapshots.created(accountCapture(
                    requireAccount(store.account(((AccountView) result).id(), true)), true)));
            case MAIL_ACCOUNT_UPDATE -> updateAccount((Long) args[0]);
            case MAIL_TEMPLATE_CREATE -> result -> List.of(snapshots.created(templateCapture(
                    requireTemplate(store.template(((TemplateView) result).id(), true)))));
            case MAIL_TEMPLATE_UPDATE -> updateTemplate((Long) args[0]);
            default -> throw new IllegalStateException("邮件审计操作未绑定");
        };
    }

    private Plan updateAccount(long id) {
        var existing = requireAccount(store.account(id, true));
        var previous = accountCapture(existing, false);
        return result -> {
            var saved = requireAccount(store.account(id, true));
            boolean credentialChanged = !Objects.equals(existing.getCredentialCiphertext(), saved.getCredentialCiphertext());
            return List.of(snapshots.updated(previous, accountCapture(saved, credentialChanged)));
        };
    }

    private Plan updateTemplate(long id) {
        var previous = templateCapture(requireTemplate(store.template(id, true)));
        return result -> List.of(snapshots.updated(previous, templateCapture(requireTemplate(store.template(id, true)))));
    }

    private AuditSnapshots.Capture accountCapture(MailAccount account, boolean credentialChanged) {
        var object = new AuditObject("MAIL_ACCOUNT", account.getId().toString(), account.getCode(), account.getName(), null, null);
        return snapshots.capture(object, Map.of(
                "code", AuditField.visible("编码", account.getCode()),
                "name", AuditField.visible("名称", account.getName()),
                "provider", AuditField.visible("接入方式", account.getProvider()),
                "mailboxAddress", AuditField.visible("邮箱地址", account.getMailboxAddress()),
                "config", AuditField.visible("连接参数", json.readValue(account.getConfigJson(), Settings.class)),
                "credential", AuditField.hidden("邮箱凭据", credentialChanged),
                "enable", AuditField.visible("启用", account.getEnable())));
    }

    private AuditSnapshots.Capture templateCapture(MailTemplate template) {
        var object = new AuditObject("MAIL_TEMPLATE", template.getId().toString(), template.getCode(), template.getName(), null, null);
        return snapshots.capture(object, Map.of(
                "code", AuditField.visible("编码", template.getCode()),
                "name", AuditField.visible("名称", template.getName()),
                "accountId", AuditField.visible("邮箱配置", template.getAccountId().toString()),
                "bodyFormat", AuditField.visible("正文格式", template.getBodyFormat()),
                "sendMode", AuditField.visible("发送方式", template.getSendMode()),
                "revision", AuditField.visible("版本", template.getRevision()),
                "enable", AuditField.visible("启用", template.getEnable())));
    }
}
