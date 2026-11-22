package me.lendev.flow.mail;

import static me.lendev.flow.mail.persistence.table.MailAccountTableDef.MAIL_ACCOUNT;
import static me.lendev.flow.mail.persistence.table.MailSendDetailTableDef.MAIL_SEND_DETAIL;
import static me.lendev.flow.mail.persistence.table.MailSendLogTableDef.MAIL_SEND_LOG;
import static me.lendev.flow.mail.persistence.table.MailTemplateTableDef.MAIL_TEMPLATE;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.mail.MailAdminModels.Page;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RecipientResult;
import me.lendev.flow.mail.MailModels.SendMode;
import me.lendev.flow.mail.MailModels.Status;
import me.lendev.flow.mail.MailModels.Submission;
import me.lendev.flow.mail.mapper.MailAccountMapper;
import me.lendev.flow.mail.mapper.MailSendDetailMapper;
import me.lendev.flow.mail.mapper.MailSendLogMapper;
import me.lendev.flow.mail.mapper.MailTemplateMapper;
import me.lendev.flow.mail.persistence.MailAccount;
import me.lendev.flow.mail.persistence.MailSendDetail;
import me.lendev.flow.mail.persistence.MailSendLog;
import me.lendev.flow.mail.persistence.MailTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 持久化邮箱、模板和邮件发送记录。发送状态变动锁定同一主记录，外部提交在事务外执行。
 */
@Repository
@Slf4j
public class MailStore {

    private static final int EXPIRY_BATCH = 100;
    private static final String EXPIRED_UNCERTAIN = "MAIL_SUBMISSION_EXPIRED";
    private static final String EXPIRED_UNSUBMITTED = "MAIL_NOT_SUBMITTED";
    private static final Pattern PROVIDER_ID = Pattern.compile("[-A-Za-z0-9_.:@<>+/=]{1,200}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final TypeReference<List<Recipient>> RECIPIENTS = new TypeReference<>() {
    };
    private static final TypeReference<List<RecipientResult>> RECIPIENT_RESULTS = new TypeReference<>() {
    };

    private final MailAccountMapper accounts;
    private final MailTemplateMapper templates;
    private final MailSendLogMapper logs;
    private final MailSendDetailMapper details;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public MailStore(MailAccountMapper accounts, MailTemplateMapper templates, MailSendLogMapper logs,
                     MailSendDetailMapper details, ObjectMapper json, Clock clock, PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.templates = templates;
        this.logs = logs;
        this.details = details;
        this.json = json;
        this.clock = clock;

        this.write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
    }

    public record SendReservation(long accountId, long templateId, String accountCode, String templateCode,
                                  int templateRevision, String senderAddress, Provider provider, SendMode sendMode,
                                  String callerKey,
                                  String requestId, String idempotencyKey, String requestDigest, int recipientCount) {
    }

    public record SendLog(long id, long accountId, long templateId, String accountCode, String templateCode,
                          int templateRevision, String senderAddress, Provider provider, SendMode sendMode,
                          String callerKey,
                          String requestId, @JsonIgnore String requestDigest, int recipientCount, Status status,
                          Instant createdAt, Instant finishedAt) {
    }

    public record SendDetail(long id, int sequenceNo, List<Recipient> recipients,
                             List<RecipientResult> recipientResults,
                             Status status, String providerRequestId, String errorCode, String errorMessage,
                             Instant startedAt, Instant finishedAt) {
    }

    public record StoredSend(SendLog log, List<SendDetail> details, boolean created) {
    }

    /**
     * 在一致的数据库快照中读取调用方已有的幂等请求及其提交明细。
     */
    public Optional<StoredSend> findSend(String callerKey, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return read.execute(tx -> Optional.ofNullable(logs.selectOneByQuery(QueryWrapper.create()
                        .where(MAIL_SEND_LOG.CALLER_KEY.eq(callerKey))
                        .and(MAIL_SEND_LOG.IDEMPOTENCY_KEY.eq(idempotencyKey))))
                .map(log -> stored(log, false)));
    }

    /**
     * 原子地提交发送意图和全部待发明细。同一调用方和幂等键发生竞争时，只返回一条已创建的结果；
     * 其他调用方在独立事务中读取赢家，由业务层比较请求摘要。
     */
    public StoredSend reserve(SendReservation reservation, List<List<Recipient>> recipientGroups) {
        try {
            return write.execute(tx -> insertReservation(reservation, recipientGroups));
        } catch (DuplicateKeyException conflict) {
            return findSend(reservation.callerKey(), reservation.idempotencyKey()).orElseThrow(() -> conflict);
        }
    }

    private StoredSend insertReservation(SendReservation reservation, List<List<Recipient>> recipientGroups) {
        var log = new MailSendLog();
        log.setAccountId(reservation.accountId());
        log.setTemplateId(reservation.templateId());
        log.setAccountCode(reservation.accountCode());
        log.setTemplateCode(reservation.templateCode());
        log.setTemplateRevision(reservation.templateRevision());
        log.setSenderAddress(reservation.senderAddress());
        log.setProvider(reservation.provider());
        log.setSendMode(reservation.sendMode());
        log.setCallerKey(reservation.callerKey());
        log.setRequestId(reservation.requestId());
        log.setIdempotencyKey(reservation.idempotencyKey());
        log.setRequestDigest(reservation.requestDigest());
        log.setRecipientCount(reservation.recipientCount());
        log.setStatus(Status.PENDING);
        log.setCreatedAt(now());
        logs.insert(log);

        for (int index = 0; index < recipientGroups.size(); index++) {
            var detail = new MailSendDetail();
            detail.setSendLogId(log.getId());
            detail.setSequenceNo(index + 1);
            detail.setRecipientsJson(json.writeValueAsString(recipientGroups.get(index)));
            detail.setStatus(Status.PENDING);
            details.insert(detail);
        }
        return stored(logs.selectOneById(log.getId()), true);
    }

    /**
     * 仅待提交明细可以领取。提交中状态在调用服务商之前提交到数据库。
     */
    public boolean claim(long detailId) {
        return Boolean.TRUE.equals(write.execute(tx -> {
            MailSendLog log = lockForDetail(detailId);
            if (log == null || terminal(log.getStatus())) {
                return false;
            }
            var patch = UpdateEntity.of(MailSendDetail.class);
            patch.setStatus(Status.PROCESSING);
            patch.setStartedAt(now());
            if (details.updateByCondition(patch,
                    MAIL_SEND_DETAIL.ID.eq(detailId).and(MAIL_SEND_DETAIL.STATUS.eq(Status.PENDING))) == 0) {
                return false;
            }
            if (log.getStatus() == Status.PENDING) {
                log.setStatus(Status.PROCESSING);
                logs.update(log);
            }
            return true;
        }));
    }

    /**
     * 保存已领取明细的最终状态、服务商关联标识及安全结果。过期后到达的结果不覆盖原记录。
     */
    public void finish(long detailId, Submission submission) {
        if (!terminal(submission.status())) {
            throw new IllegalArgumentException("服务商提交必须返回最终状态");
        }
        complete(detailId, Status.PROCESSING, submission);
    }

    /**
     * 结束确认未尝试的待提交明细。
     */
    public void skip(long detailId, String errorCode, String errorMessage) {
        complete(detailId, Status.PENDING, new Submission(Status.FAILED, null, errorCode, errorMessage, List.of()));
    }

    private void complete(long detailId, Status previous, Submission submission) {
        write.executeWithoutResult(tx -> {
            MailSendLog log = lockForDetail(detailId);
            if (log != null && !terminal(log.getStatus()) && updateDetail(MAIL_SEND_DETAIL.ID.eq(detailId),
                    previous, submission, now()) != 0) {
                aggregate(log);
            }
        });
    }

    /**
     * 从一个数据库快照读取主记录和有序明细。
     */
    public StoredSend send(long logId) {
        return read.execute(tx -> {
            MailSendLog log = logs.selectOneById(logId);
            if (log == null) {
                throw new MailOperationException(HttpStatus.NOT_FOUND, "MAIL_LOG_NOT_FOUND", "发送日志不存在");
            }
            return stored(log, false);
        });
    }

    /**
     * 按主记录的创建时间小批量恢复过期请求。已开始的提交记为未知，尚未开始的提交记为失败。
     * 每条请求单独完成事务，领取和结束提交都遵守相同的行锁顺序。
     *
     * @param cutoff 仅结案创建时间早于该时点的未完成请求
     * @return 本批实际结案的发送记录数量
     */
    public int expire(Instant cutoff) {
        var deadline = LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        var candidates = logs.selectObjectListByQueryAs(QueryWrapper.create().select(MAIL_SEND_LOG.ID)
                .from(MAIL_SEND_LOG).where(MAIL_SEND_LOG.STATUS.in(Status.PENDING, Status.PROCESSING))
                .and(MAIL_SEND_LOG.CREATED_AT.lt(deadline))
                .orderBy(MAIL_SEND_LOG.CREATED_AT.asc(), MAIL_SEND_LOG.ID.asc()).limit(EXPIRY_BATCH), Long.class);
        int expired = 0;
        for (long id : candidates) {
            if (Boolean.TRUE.equals(write.execute(tx -> expireLog(id, deadline)))) {
                expired++;
                log.warn("邮件发送批次超时并已结案，请按发送日志 ID 检查明细，对 UNKNOWN 明细先核对服务商是否受理，logId={} createdBefore={}",
                        id, cutoff);
            }
        }
        return expired;
    }

    private boolean expireLog(long id, LocalDateTime deadline) {
        MailSendLog log = logs.selectOneByQuery(QueryWrapper.create().where(MAIL_SEND_LOG.ID.eq(id)).forUpdate());
        if (log == null || terminal(log.getStatus()) || !log.getCreatedAt().isBefore(deadline)) {
            return false;
        }
        var send = MAIL_SEND_DETAIL.SEND_LOG_ID.eq(id);
        var finished = now();
        updateDetail(send, Status.PENDING,
                new Submission(Status.FAILED, null, EXPIRED_UNSUBMITTED, "请求已过期，邮件未提交", List.of()), finished);
        updateDetail(send, Status.PROCESSING,
                new Submission(Status.UNKNOWN, null, EXPIRED_UNCERTAIN, "请求已过期，无法确认服务商是否受理", List.of()), finished);
        aggregate(log);
        return true;
    }

    private MailSendLog lockForDetail(long detailId) {
        var send = QueryWrapper.create().select(MAIL_SEND_DETAIL.SEND_LOG_ID).from(MAIL_SEND_DETAIL)
                .where(MAIL_SEND_DETAIL.ID.eq(detailId));
        return logs.selectOneByQuery(QueryWrapper.create().where(MAIL_SEND_LOG.ID.eq(send)).forUpdate());
    }

    private int updateDetail(QueryCondition target, Status previous, Submission submission,
                             LocalDateTime finished) {
        var patch = UpdateEntity.of(MailSendDetail.class);
        patch.setStatus(submission.status());
        patch.setProviderRequestId(match(PROVIDER_ID, submission.providerRequestId()));
        patch.setErrorCode(match(ERROR_CODE, submission.errorCode()));
        patch.setErrorMessage(submission.errorMessage());
        if (submission.recipientResults() != null && !submission.recipientResults().isEmpty()) {
            patch.setRecipientResultsJson(json.writeValueAsString(submission.recipientResults()));
        }
        patch.setFinishedAt(finished);
        return details.updateByCondition(patch, target.clone().and(MAIL_SEND_DETAIL.STATUS.eq(previous)));
    }

    private void aggregate(MailSendLog log) {
        var statuses = details.selectListByQuery(QueryWrapper.create().select(MAIL_SEND_DETAIL.STATUS)
                        .where(MAIL_SEND_DETAIL.SEND_LOG_ID.eq(log.getId())).forUpdate())
                .stream().map(MailSendDetail::getStatus).toList();
        if (statuses.contains(Status.PENDING) || statuses.contains(Status.PROCESSING)) {
            log.setStatus(Status.PROCESSING);
        } else {
            log.setStatus(aggregateTerminal(statuses));
            log.setFinishedAt(now());
        }
        logs.update(log);
    }

    private static Status aggregateTerminal(List<Status> statuses) {
        if (statuses.contains(Status.UNKNOWN)) {
            return Status.UNKNOWN;
        }
        if (statuses.contains(Status.PARTIAL) || statuses.contains(Status.ACCEPTED) && statuses.contains(Status.FAILED)) {
            return Status.PARTIAL;
        }
        return statuses.contains(Status.ACCEPTED) ? Status.ACCEPTED : Status.FAILED;
    }

    private static boolean terminal(Status status) {
        return status != null && status != Status.PENDING && status != Status.PROCESSING;
    }

    private static String match(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches() ? value : null;
    }

    private StoredSend stored(MailSendLog log, boolean created) {
        List<SendDetail> entries = details.selectListByQuery(QueryWrapper.create()
                        .where(MAIL_SEND_DETAIL.SEND_LOG_ID.eq(log.getId())).orderBy(MAIL_SEND_DETAIL.SEQUENCE_NO.asc()))
                .stream().map(this::view).toList();
        return new StoredSend(view(log), entries, created);
    }

    private SendDetail view(MailSendDetail row) {
        var results = row.getRecipientResultsJson() == null ? List.<RecipientResult>of()
                : json.readValue(row.getRecipientResultsJson(), RECIPIENT_RESULTS);
        return new SendDetail(row.getId(), row.getSequenceNo(), json.readValue(row.getRecipientsJson(), RECIPIENTS),
                results, row.getStatus(), row.getProviderRequestId(), row.getErrorCode(), row.getErrorMessage(),
                instant(row.getStartedAt()), instant(row.getFinishedAt()));
    }

    private static SendLog view(MailSendLog row) {
        return new SendLog(row.getId(), row.getAccountId(), row.getTemplateId(), row.getAccountCode(), row.getTemplateCode(),
                row.getTemplateRevision(), row.getSenderAddress(), row.getProvider(), row.getSendMode(), row.getCallerKey(),
                row.getRequestId(), row.getRequestDigest(), row.getRecipientCount(), row.getStatus(),
                instant(row.getCreatedAt()), instant(row.getFinishedAt()));
    }

    Page<SendLog> listLogs(int pageNo, int pageSize, Long accountId, Long templateId, Status status, Instant from, Instant to) {
        var query = QueryWrapper.create();
        if (accountId != null) {
            query.and(MAIL_SEND_LOG.ACCOUNT_ID.eq(accountId));
        }
        if (templateId != null) {
            query.and(MAIL_SEND_LOG.TEMPLATE_ID.eq(templateId));
        }
        if (status != null) {
            query.and(MAIL_SEND_LOG.STATUS.eq(status));
        }
        if (from != null) {
            query.and(MAIL_SEND_LOG.CREATED_AT.ge(LocalDateTime.ofInstant(from, ZoneOffset.UTC)));
        }
        if (to != null) {
            query.and(MAIL_SEND_LOG.CREATED_AT.lt(LocalDateTime.ofInstant(to, ZoneOffset.UTC)));
        }
        var rows = page(logs, query.orderBy(MAIL_SEND_LOG.CREATED_AT.desc(), MAIL_SEND_LOG.ID.desc()), pageNo, pageSize);
        return new Page<>(rows.records().stream().map(MailStore::view).toList(), rows.total());
    }

    Page<MailAccount> listAccounts(int pageNo, int pageSize, String keyword, Boolean enabled) {
        return page(accounts, searchable(keyword, enabled).orderBy(MAIL_ACCOUNT.ID.desc()), pageNo, pageSize);
    }

    Page<MailTemplate> listTemplates(int pageNo, int pageSize, String keyword, Boolean enabled) {
        return page(templates, searchable(keyword, enabled).orderBy(MAIL_TEMPLATE.ID.desc()), pageNo, pageSize);
    }

    private static QueryWrapper searchable(String keyword, Boolean enabled) {
        var query = QueryWrapper.create();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            query.and("(LOWER(`code`) LIKE LOWER(?) ESCAPE '!' OR LOWER(`name`) LIKE LOWER(?) ESCAPE '!')", like, like);
        }
        if (enabled != null) {
            query.and("enable = ?", enabled);
        }
        return query;
    }

    private static <T> Page<T> page(BaseMapper<T> mapper, QueryWrapper query, int pageNo, int pageSize) {
        var rows = mapper.paginate(pageNo, pageSize, query);
        return new Page<>(rows.getRecords(), rows.getTotalRow());
    }

    List<MailAccount> accountOptions() {
        return accounts.selectListByQuery(optionQuery());
    }

    List<MailTemplate> templateOptions() {
        return templates.selectListByQuery(optionQuery());
    }

    private static QueryWrapper optionQuery() {
        return QueryWrapper.create().select("id", "code", "name", "enable").orderBy("code", true);
    }

    MailAccount account(long id, boolean lock) {
        var query = QueryWrapper.create().where(MAIL_ACCOUNT.ID.eq(id));
        return accounts.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    MailAccount account(String code) {
        return accounts.selectOneByQuery(QueryWrapper.create().where(MAIL_ACCOUNT.CODE.eq(code)));
    }

    MailTemplate template(long id, boolean lock) {
        var query = QueryWrapper.create().where(MAIL_TEMPLATE.ID.eq(id));
        return templates.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    MailTemplate template(String code) {
        return templates.selectOneByQuery(QueryWrapper.create().where(MAIL_TEMPLATE.CODE.eq(code)));
    }

    void saveAccount(MailAccount account) {
        if (account.getId() == null) {
            accounts.insert(account);
        } else {
            accounts.update(account);
        }
    }

    void saveTemplate(MailTemplate template) {
        if (template.getId() == null) {
            templates.insert(template);
        } else {
            templates.update(template);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
