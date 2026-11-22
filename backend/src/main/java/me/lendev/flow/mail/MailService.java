package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.*;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.mail.MailStore.SendReservation;
import me.lendev.flow.mail.MailStore.StoredSend;
import me.lendev.flow.mail.MailTemplateRenderer.PreparedSubmission;
import me.lendev.flow.mail.transport.MailTransport;
import me.lendev.flow.mail.transport.MailTransportException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 统一处理邮箱查询、模板渲染和带持久日志的邮件提交。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MailService {
    private static final Duration SEND_BUDGET = Duration.ofSeconds(60);
    private static final Duration RECOVERY_AGE = Duration.ofMinutes(30);
    private static final Set<String> WORKFLOW_OPTIONAL_VARIABLES = Set.of("targetNode.name");
    private final MailAdminService admin;
    private final MailStore store;
    private final MailTemplateRenderer renderer;
    private final MailSecurity security;
    private final List<MailTransport> transports;
    private final Clock clock;

    /**
     * 只读查询收件箱，游标与调用方、邮箱配置及筛选条件绑定。
     *
     * @param accountCode 邮箱配置编码
     * @param input       收件箱的筛选和分页参数
     * @param cursor      上一页的后续页游标，首次查询传 {@code null}
     * @param callerKey   调用方标识，用于限制游标的使用范围
     * @param requestId   用于关联调用日志的请求 ID
     * @return 本页邮件和可用的后续页游标
     * @throws MailOperationException 邮箱不可用、游标无效或远程读取失败
     */
    public InboxPage inbox(String accountCode, InboxQuery input, String cursor, String callerKey, String requestId) {
        if (input.receivedFrom() != null && input.receivedTo() != null
                && !input.receivedFrom().isBefore(input.receivedTo())) {
            throw new BadRequestException("接收起始时间必须早于结束时间");
        }
        var query = new InboxQuery(nonBlank(input.sender()), nonBlank(input.subject()), input.receivedFrom(),
                input.receivedTo(), input.read(), input.includeBody(), input.pageSize());
        var account = admin.loadAccountConnection(accountCode);
        var connection = account.connection();
        var scope = new InboxScope(account.id(), callerKey, connection.provider(), connection.mailboxAddress(),
                connection.settings(), query);
        String continuation = security.continuation(scope, cursor);
        long started = System.nanoTime();
        try {
            var page = transport(connection.provider()).read(connection, query, continuation);
            String nextCursor = security.cursor(scope, page.nextCursor());
            log.info("Mail inbox read requestId={} accountId={} provider={} count={} hasMore={} elapsedMs={}",
                    requestId, account.id(), connection.provider(), page.messages().size(), nextCursor != null, elapsed(started));
            return new InboxPage(page.messages(), nextCursor);
        } catch (MailTransportException problem) {
            HttpStatus status = "MAIL_INBOX_CURSOR_INVALID".equals(problem.code())
                    ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
            if (status == HttpStatus.BAD_GATEWAY) {
                log.warn("邮箱读取失败，请按邮箱 ID 检查连接配置、凭据有效期及服务可用性，requestId={} accountId={} provider={} errorCode={} elapsedMs={}",
                        requestId, account.id(), connection.provider(), problem.code(), elapsed(started));
            } else {
                log.info("邮箱查询游标无效，requestId={} accountId={} provider={} errorCode={}",
                        requestId, account.id(), connection.provider(), problem.code());
            }
            throw new MailOperationException(status, problem.code(), problem.getMessage());
        }
    }

    /**
     * 渲染样例邮件，用与正式发送相同的模板规则。
     */
    public RenderedMail preview(PreviewRequest request) {
        var sample = request.recipient();
        var recipient = sample == null ? null : new Recipient(sample.address(), sample.name());
        return renderer.render(request.subjectTemplate(), request.bodyTemplate(), request.sendMode(),
                request.variables(), recipient);
    }

    /**
     * 先持久化请求及提交计划，再在数据库事务之外调用邮件服务。
     * 同一调用方的幂等键只创建一条主日志，结果不确定的邮件保留记录供查询。
     *
     * @param request   模板、收件人、渲染变量和幂等键
     * @param callerKey 限定幂等键作用域的调用方标识
     * @param requestId 用于关联持久化记录和日志的请求 ID
     * @return 本次提交结果，幂等重放时返回已记录的结果
     */
    public SendResult send(SendRequest request, String callerKey, String requestId) {
        String templateCode = request.templateCode().strip().toUpperCase(Locale.ROOT);
        var template = new MailSendTemplate(templateCode, () -> admin.loadTemplate(templateCode), Set.of());
        return send(template, request.recipients(), request.variables(), request.idempotencyKey(), callerKey, requestId);
    }

    /**
     * 使用固定邮件模板 ID 提交单收件人的流程通知。重试须提供相同幂等键及固定参数。
     * 摘要按模板 ID 计算；首次预约时记录实际读取的模板修订号。
     *
     * @param templateId     已登记的邮件模板 ID
     * @param recipient      单个通知收件人
     * @param variables      模板渲染变量
     * @param callerKey      限定幂等键作用域的调用方标识
     * @param idempotencyKey 流程通知的固定幂等键
     * @param requestId      用于关联持久化记录和日志的请求 ID
     * @return 首次提交或幂等重放的发送结果
     */
    public SendResult sendByTemplateId(long templateId, Recipient recipient, Map<String, String> variables,
                                       String callerKey, String idempotencyKey, String requestId) {
        Objects.requireNonNull(idempotencyKey);
        var template = new MailSendTemplate(templateId, () -> admin.loadTemplate(templateId), WORKFLOW_OPTIONAL_VARIABLES);
        return send(template, List.of(recipient), variables, idempotencyKey, callerKey, requestId);
    }

    /**
     * 按原调用方和幂等键查对账信息，只返回日志 ID、状态和错误码。
     */
    public Optional<SubmissionReceipt> findSubmission(String callerKey, String idempotencyKey) {
        return store.findSend(callerKey, idempotencyKey).map(stored -> new SubmissionReceipt(
                stored.log().id(), stored.log().status(),
                stored.details().stream().map(MailStore.SendDetail::errorCode).filter(Objects::nonNull)
                        .findFirst().orElse(null)));
    }

    /**
     * 提交日志的内部对账摘要。
     */
    public record SubmissionReceipt(long logId, Status status, String errorCode) {
    }

    private SendResult send(MailSendTemplate source, List<Recipient> recipients, Map<String, String> inputVariables,
                            String key, String callerKey, String requestId) {
        long started = System.nanoTime();
        checkRecipients(recipients);
        Map<String, String> variables = inputVariables == null ? Map.of() : new TreeMap<>(inputVariables);
        String digest = key == null ? null : security.digest(List.of(source.key(), recipients, variables));
        if (key != null) {
            var existing = store.findSend(callerKey, key);
            if (existing.isPresent()) {
                return replay(existing.get(), digest, requestId);
            }
        }

        var template = source.load().get();
        var connection = template.connection();
        var prepared = renderer.renderForRecipients(template.subjectTemplate(), template.bodyTemplate(), template.sendMode(),
                recipients, variables, source.optionalVariables());
        var adapter = transport(connection.provider());
        var reservation = new SendReservation(template.accountId(), template.id(), template.accountCode(), template.code(),
                template.revision(), connection.mailboxAddress(), connection.provider(), template.sendMode(),
                callerKey, requestId, key, digest, recipients.size());
        var stored = store.reserve(reservation, prepared.stream().map(PreparedSubmission::recipients).toList());
        if (!stored.created()) {
            return replay(stored, digest, requestId);
        }

        long logId = stored.log().id();
        log.info("Mail send started requestId={} logId={} accountId={} templateId={} provider={} mode={} recipients={}",
                requestId, logId, template.accountId(), template.id(), connection.provider(), template.sendMode(), recipients.size());
        for (var detail : stored.details()) {
            if (System.nanoTime() - started >= SEND_BUDGET.toNanos()) {
                store.skip(detail.id(), "MAIL_SEND_BUDGET_EXHAUSTED", "本次发送已达到处理时限，此明细未提交");
            } else if (store.claim(detail.id())) {
                var item = prepared.get(detail.sequenceNo() - 1);
                submit(adapter, connection, item, logId, detail.id(), requestId);
            }
        }
        var result = result(store.send(logId));
        if (result.status() == Status.FAILED || result.status() == Status.PARTIAL || result.status() == Status.UNKNOWN) {
            log.warn("邮件批次存在未成功或未确认明细，请按发送日志 ID 检查明细错误码和服务商回执，确认实际受理状态后再补发，requestId={} logId={} accountId={} templateId={} provider={} status={} elapsedMs={}",
                    requestId, logId, template.accountId(), template.id(), connection.provider(), result.status(), elapsed(started));
        } else {
            log.info("邮件批次处理结束，requestId={} logId={} accountId={} templateId={} provider={} status={} elapsedMs={}",
                    requestId, logId, template.accountId(), template.id(), connection.provider(), result.status(), elapsed(started));
        }
        return result;
    }

    /**
     * 归档超时遗留的提交状态，外部服务结果未知时由日志保留该事实。
     */
    @Scheduled(fixedDelayString = "${flow.mail.recovery-interval-ms:60000}")
    public void recoverAbandonedSends() {
        int count = store.expire(clock.instant().minus(RECOVERY_AGE));
        if (count > 0) {
            log.info("Mail send recovery finalized logs={}", count);
        }
    }

    private void submit(MailTransport adapter, Connection connection, PreparedSubmission item, long logId,
                        long detailId, String requestId) {
        Submission submission;
        long started = System.nanoTime();
        try {
            submission = adapter.send(connection, item.recipients(), item.mail());
        } catch (RuntimeException providerProblem) {
            log.warn("邮件提交过程中断，受理结果未知，请按发送日志和明细 ID 核对邮件服务记录后决定是否补发，requestId={} logId={} detailId={} provider={} errorType={}",
                    requestId, logId, detailId, connection.provider(), providerProblem.getClass().getSimpleName());
            submission = new Submission(Status.UNKNOWN, null, "MAIL_PROVIDER_UNCERTAIN", "无法确认邮件服务是否受理", null);
        }
        store.finish(detailId, submission);
        log.info("邮件提交结果已记录，requestId={} logId={} detailId={} provider={} status={} errorCode={} elapsedMs={}",
                requestId, logId, detailId, connection.provider(), submission.status(), submission.errorCode(), elapsed(started));
    }

    private static void checkRecipients(List<Recipient> recipients) {
        var addresses = new HashSet<String>();
        for (var recipient : recipients) {
            if (!addresses.add(recipient.address().toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("收件人邮箱地址重复");
            }
        }
    }

    private SendResult replay(StoredSend stored, String digest, String requestId) {
        if (!Objects.equals(digest, stored.log().requestDigest())) {
            throw new MailOperationException(HttpStatus.CONFLICT, "MAIL_IDEMPOTENCY_CONFLICT", "该幂等键已用于不同的发送请求");
        }
        var result = result(stored);
        log.info("Mail send replayed requestId={} logId={} status={}", requestId, result.logId(), result.status());
        return result;
    }

    private static SendResult result(StoredSend stored) {
        return new SendResult(stored.log().id(), stored.log().status());
    }

    private MailTransport transport(Provider provider) {
        return transports.stream().filter(adapter -> adapter.provider() == provider).findFirst()
                .orElseThrow(() -> new MailOperationException(HttpStatus.SERVICE_UNAVAILABLE,
                        "MAIL_PROVIDER_UNAVAILABLE", "邮件接入服务暂不可用"));
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record InboxScope(long accountId, String callerKey, Provider provider, String mailboxAddress,
                              Settings settings, InboxQuery query) {
    }
}
