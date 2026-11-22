package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailAdminModels.*;
import static me.lendev.flow.mail.MailModels.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.mail.MailStore.SendLog;
import me.lendev.flow.mail.persistence.MailAccount;
import me.lendev.flow.mail.persistence.MailTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 校验和管理邮件配置、模板及历史记录，并为邮件业务接口提供已启用的连接。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailAdminService {

    private static final String BODY_FORMAT = "MARKDOWN";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private final MailStore store;
    private final MailTemplateRenderer renderer;
    private final MailAccountConfiguration configuration;
    private final Clock clock;

    public record AccountConnection(long id, String code, Connection connection) {
    }

    public record BoundTemplate(long id, String code, long accountId, String accountCode, int revision,
                                String subjectTemplate, String bodyTemplate, SendMode sendMode, Connection connection) {
    }

    Page<AccountView> listAccounts(int pageNo, int pageSize, String keyword, Boolean enabled) {
        var rows = store.listAccounts(pageNo, pageSize, keyword, enabled);
        return new Page<>(rows.records().stream().map(this::accountView).toList(), rows.total());
    }

    Page<TemplateView> listTemplates(int pageNo, int pageSize, String keyword, Boolean enabled) {
        var rows = store.listTemplates(pageNo, pageSize, keyword, enabled);
        return new Page<>(rows.records().stream().map(MailAdminService::templateView).toList(), rows.total());
    }

    List<Option> accountOptions() {
        return store.accountOptions().stream()
                .map(account -> new Option(account.getId(), account.getCode(), account.getName(), account.getEnable()))
                .toList();
    }

    LogOptions logOptions() {
        return new LogOptions(accountOptions(), store.templateOptions().stream()
                .map(template -> new Option(template.getId(), template.getCode(), template.getName(), template.getEnable()))
                .toList());
    }

    Page<SendLog> listLogs(int pageNo, int pageSize, Long accountId, Long templateId, Status status, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("MAIL_LOG_TIME_RANGE", "开始时间必须早于结束时间");
        }
        return store.listLogs(pageNo, pageSize, accountId, templateId, status, from, to);
    }

    LogView log(long id) {
        var stored = store.send(id);
        return new LogView(stored.log(), stored.details());
    }

    /**
     * 新建邮箱配置并在同一事务内记录操作审计。
     */
    @Audited(AuditOperation.MAIL_ACCOUNT_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public AccountView createAccount(AccountRequest request, long actor) {
        return saveAccount(new MailAccount(), request, actor);
    }

    /**
     * 更新邮箱配置。认证目标变更时需填写对应凭据，接入方式在创建后固定。
     *
     * @param id      邮箱配置 ID
     * @param request 新配置和对应的替换凭据
     * @param actor   操作用户 ID
     * @return 保存后的管理视图
     * @throws MailOperationException 邮箱不存在、配置无效或接入方式发生变化
     */
    @Audited(AuditOperation.MAIL_ACCOUNT_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public AccountView updateAccount(Long id, AccountRequest request, long actor) {
        var row = requireAccount(store.account(id, true));
        if (request.provider() != row.getProvider()) {
            throw new MailOperationException(HttpStatus.CONFLICT, "MAIL_ACCOUNT_PROVIDER_IMMUTABLE", "邮箱的接入方式在创建后不可修改");
        }
        return saveAccount(row, request, actor);
    }

    private AccountView saveAccount(MailAccount row, AccountRequest request, long actor) {
        var settings = MailAccountConfiguration.validateSettings(request);
        String code = normalizedCode(request.code());
        String ciphertext = configuration.nextCiphertext(row, request.provider(), settings, request.credentials());
        boolean create = row.getId() == null;
        var timestamp = now();
        if (create) {
            row.setCreatedAt(timestamp);
            row.setCreatedBy(actor);
        }
        row.setProvider(request.provider());
        row.setCode(code);
        row.setName(request.name().strip());
        row.setMailboxAddress(request.mailboxAddress());
        row.setConfigJson(configuration.writeSettings(settings));
        row.setEnable(request.enable());
        row.setCredentialCiphertext(ciphertext);
        row.setUpdatedAt(timestamp);
        row.setUpdatedBy(actor);
        try {
            store.saveAccount(row);
        } catch (DuplicateKeyException conflict) {
            throw new MailOperationException(HttpStatus.CONFLICT, "MAIL_ACCOUNT_CODE_CONFLICT", "邮箱配置编码已存在");
        }
        log.info("邮箱配置已写入，requestId={} accountId={} provider={} enabled={} created={}",
                AuditParticipants.currentRequestId(), row.getId(), row.getProvider(), row.getEnable(), create);
        return accountView(row);
    }

    /**
     * 新建绑定于已启用邮箱的 Markdown 模板。
     */
    @Audited(AuditOperation.MAIL_TEMPLATE_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public TemplateView createTemplate(TemplateRequest request, long actor) {
        return saveTemplate(new MailTemplate(), request, actor);
    }

    /**
     * 修改 Markdown 模板。内容、绑定或发送方式变化会递增版本。
     *
     * @param id      邮件模板 ID
     * @param request 模板内容、绑定邮箱和发送方式
     * @param actor   操作用户 ID
     * @return 带当前内容版本的模板视图
     * @throws MailOperationException 模板或邮箱不存在，或新配置无效
     */
    @Audited(AuditOperation.MAIL_TEMPLATE_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public TemplateView updateTemplate(Long id, TemplateRequest request, long actor) {
        return saveTemplate(requireTemplate(store.template(id, true)), request, actor);
    }

    private TemplateView saveTemplate(MailTemplate row, TemplateRequest request, long actor) {
        var account = requireAccount(store.account(request.accountId(), false));
        if (!Boolean.TRUE.equals(account.getEnable()) && !Objects.equals(row.getAccountId(), request.accountId())) {
            throw invalid("MAIL_TEMPLATE_ACCOUNT_DISABLED", "请选择已启用的发件邮箱");
        }
        SendMode mode = request.sendMode() != null ? request.sendMode()
                : Objects.requireNonNullElse(row.getSendMode(), SendMode.INDIVIDUAL);
        renderer.validate(request.subjectTemplate(), request.bodyTemplate(), mode);

        var timestamp = now();
        if (row.getId() == null) {
            row.setRevision(1);
            row.setCreatedAt(timestamp);
            row.setCreatedBy(actor);
        } else if (contentChanged(row, request, mode)) {
            row.setRevision(row.getRevision() + 1);
        }
        row.setCode(normalizedCode(request.code()));
        row.setName(request.name().strip());
        row.setAccountId(request.accountId());
        row.setSubjectTemplate(request.subjectTemplate());
        row.setBodyTemplate(request.bodyTemplate());
        row.setBodyFormat(BODY_FORMAT);
        row.setSendMode(mode);
        row.setEnable(request.enable());
        row.setUpdatedAt(timestamp);
        row.setUpdatedBy(actor);
        try {
            store.saveTemplate(row);
        } catch (DuplicateKeyException conflict) {
            throw new MailOperationException(HttpStatus.CONFLICT, "MAIL_TEMPLATE_CODE_CONFLICT", "邮件模板编码已存在");
        }
        log.info("邮件模板配置已写入，requestId={} templateId={} accountId={} revision={} mode={} enabled={}",
                AuditParticipants.currentRequestId(), row.getId(), row.getAccountId(), row.getRevision(), mode, row.getEnable());
        return templateView(row);
    }

    private static boolean contentChanged(MailTemplate row, TemplateRequest request, SendMode mode) {
        return !row.getAccountId().equals(request.accountId())
                || !row.getSubjectTemplate().equals(request.subjectTemplate())
                || !row.getBodyTemplate().equals(request.bodyTemplate()) || row.getSendMode() != mode;
    }

    /**
     * 读取已启用邮箱的连接参数和运行时凭据，供收件接口使用。
     *
     * @param code 邮箱配置编码
     * @return 仅供内部传输使用的连接信息
     * @throws MailOperationException 邮箱不存在、已停用或连接配置无效
     */
    public AccountConnection loadAccountConnection(String code) {
        var account = requireAccount(store.account(normalizedCode(code)));
        return new AccountConnection(account.getId(), account.getCode(), connection(account));
    }

    /**
     * 从一致快照读取模板版本和绑定邮箱，并验证两者在读取时均启用。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BoundTemplate loadTemplate(String code) {
        return bindTemplate(requireTemplate(store.template(normalizedCode(code))));
    }

    /**
     * 按不可变主键读取启用模板，避免模板编码变更或重用影响已登记的业务请求。
     *
     * @param id 邮件模板 ID
     * @return 同一快照下的模板版本和邮箱连接信息
     * @throws MailOperationException 模板或所绑定邮箱不可用
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BoundTemplate loadTemplate(long id) {
        return bindTemplate(requireTemplate(store.template(id, false)));
    }

    private BoundTemplate bindTemplate(MailTemplate template) {
        if (!Boolean.TRUE.equals(template.getEnable())) {
            throw new MailOperationException(HttpStatus.NOT_FOUND, "MAIL_TEMPLATE_DISABLED", "邮件模板已停用");
        }
        var account = requireAccount(store.account(template.getAccountId(), false));
        return new BoundTemplate(template.getId(), template.getCode(), account.getId(), account.getCode(),
                template.getRevision(), template.getSubjectTemplate(), template.getBodyTemplate(),
                template.getSendMode(), connection(account));
    }

    private Connection connection(MailAccount account) {
        if (!Boolean.TRUE.equals(account.getEnable())) {
            throw new MailOperationException(HttpStatus.NOT_FOUND, "MAIL_ACCOUNT_DISABLED", "邮箱配置已停用");
        }
        return configuration.connection(account);
    }

    private static String normalizedCode(String value) {
        if (value == null || !CODE.matcher(value.strip()).matches()) {
            throw invalid("MAIL_CODE_INVALID", "邮箱配置或邮件模板编码格式无效");
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private AccountView accountView(MailAccount row) {
        return new AccountView(row.getId(), row.getCode(), row.getName(), row.getProvider(), row.getMailboxAddress(),
                configuration.readSettings(row.getConfigJson()), row.getCredentialCiphertext() != null, row.getEnable(), row.getCreatedBy(),
                MailStore.instant(row.getCreatedAt()), row.getUpdatedBy(), MailStore.instant(row.getUpdatedAt()));
    }

    private static TemplateView templateView(MailTemplate row) {
        return new TemplateView(row.getId(), row.getCode(), row.getName(), row.getAccountId(),
                row.getSubjectTemplate(), row.getBodyTemplate(), row.getBodyFormat(), row.getSendMode(), row.getRevision(),
                row.getEnable(), row.getCreatedBy(), MailStore.instant(row.getCreatedAt()), row.getUpdatedBy(),
                MailStore.instant(row.getUpdatedAt()));
    }

    static MailAccount requireAccount(MailAccount account) {
        if (account == null) {
            throw new MailOperationException(HttpStatus.NOT_FOUND, "MAIL_ACCOUNT_NOT_FOUND", "邮箱配置不存在");
        }
        return account;
    }

    static MailTemplate requireTemplate(MailTemplate template) {
        if (template == null) {
            throw new MailOperationException(HttpStatus.NOT_FOUND, "MAIL_TEMPLATE_NOT_FOUND", "邮件模板不存在");
        }
        return template;
    }

    private static MailOperationException invalid(String code, String message) {
        return new MailOperationException(HttpStatus.BAD_REQUEST, code, message);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
