package me.lendev.flow.workbench.series;

import static me.lendev.flow.workbench.series.SeriesModels.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.workbench.series.SeriesDefinitions.Definition;
import me.lendev.flow.workbench.series.SeriesRepository.Stored;
import me.lendev.flow.workbench.series.SeriesSchedule.Blocked;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoAssigneeException;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoModels.Person;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoPublicDimensions;
import me.lendev.flow.workbench.todo.TodoSchedule;
import me.lendev.flow.workbench.todo.TodoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 校验周期规则、确认影响范围并按锁定的游标分批生成周期待办。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeriesService {

    private static final int BATCH_SIZE = 32;
    private static final int WINDOW_DAYS = 89;
    private static final Duration BLOCKED_DELAY = Duration.ofMinutes(10);
    private static final Duration TRADE_RECHECK = Duration.ofHours(1);
    private static final ZoneId WORKBENCH_ZONE = ZoneId.of("Asia/Shanghai");

    private final SeriesRepository repository;
    private final SeriesSchedule schedule;
    private final SeriesDefinitions definitions;
    private final TodoService todos;
    private final TodoSchedule todoSchedule;
    private final TodoPublicDimensions dimensions;
    private final SeriesChanges changes;

    /**
     * 查询周期规则列表。
     *
     * @param managed 为 true 时返回全部规则（需管理权限），否则仅返回当前登录人创建的规则
     * @return 按规则 ID 倒序排列的规则列表
     */
    public List<Rule> list(boolean managed) {
        return repository.list(managed ? null : TodoAccess.viewer());
    }

    /**
     * 预览生效日之后的前三期，交易日历缺口与已能计算的日期一起返回。
     *
     * @param request 待校验的规则内容，不落库
     * @return 预览期次、日历覆盖范围以及可能的阻塞信息
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Write request) {
        return changes.preview(definitions.normalize(request, null, false), todoSchedule.today());
    }

    /**
     * 创建规则并建立生成游标，后续由调度任务填充实例。
     *
     * @param request 规则内容，负责人缺省为当前登录人
     * @return 变更结果，before 为 null，after 为新建规则
     * @throws me.lendev.flow.common.exception.BadRequestException 规则参数或维度不合法
     * @throws TodoAssigneeException                               负责人不存在或已停用
     */
    @Audited(AuditOperation.TODO_SERIES_CREATE)
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Mutation create(Write request) {
        var owner = todos.requireActiveUser(TodoAccess.viewer());
        var definition = definitions.normalize(request, null, false);
        dimensions.validate(definition.write().dimensions(), Map.of(), false);
        Instant now = now();
        var initial = initialize(0, owner, definition, 1, 0, todoSchedule.today(), now);
        long id = repository.insert(initial, now);
        log.info("周期待办规则创建成功，规则 ID={}，负责人 ID={}，首个锚点={}", id, owner.id(), initial.rule().nextBaseDate());
        return new Mutation(null, repository.find(id).rule(), List.of(), List.of());
    }

    /**
     * 预览将取消的未来实例，并绑定请求内容、规则版本、业务日期和实例版本。
     *
     * @param id      规则 ID
     * @param request 变更动作及新规则内容（STOP 时规则内容为空）
     * @param managed 是否以管理身份操作他人规则
     * @return 含确认令牌的变更预览，提交时需原样回传令牌
     * @throws TodoNotFoundException 规则不存在或无权访问
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChangePreview changePreview(long id, ChangeRequest request, boolean managed) {
        return changes.plan(accessible(repository.find(id), managed), request.action(), request.rule(), false, managed)
                .preview();
    }

    /**
     * 规则与受影响实例全部加锁后复核预览，并原子地切换规则版本。
     * 规则内容未变化时幂等返回，不推进版本。
     *
     * @param id      规则 ID
     * @param request 确认令牌与新规则内容
     * @param managed 是否以管理身份操作他人规则
     * @return 变更前后规则及被取消的实例列表
     * @throws TodoNotFoundException 规则不存在或无权访问
     * @throws TodoConflictException 令牌与当前状态不一致，需重新预览
     */
    @Audited(AuditOperation.TODO_SERIES_EDIT)
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Mutation edit(long id, EditRequest request, boolean managed) {
        var before = accessible(repository.lock(id), managed);
        var plan = changes.plan(before, ChangeAction.EDIT, request.rule(), true, managed);
        plan.confirm(request.token());
        Rule rule = before.rule();
        if (plan.definition().write().equals(rule.rule())) {
            return Mutation.unchanged(rule);
        }
        dimensions.validate(plan.definition().write().dimensions(), rule.rule().dimensions(), false);
        Instant now = now();
        var owner = new Person(rule.ownerUserId(), rule.ownerName());
        var after = initialize(id, owner, plan.definition(),
                rule.ruleRevision() + 1, rule.version() + 1, plan.preview().cutoffDate().plusDays(1), now);
        repository.cancel(plan.preview().affected(), ChangeAction.EDIT, TodoAccess.viewer(), now);
        repository.updateRule(after, now);
        log.info("周期待办规则编辑成功，规则 ID={}，新规则版本={}，取消实例数={}", id, after.rule().ruleRevision(),
                plan.preview().affected().size());
        return new Mutation(rule, after.rule(), plan.preview().affected(), List.of());
    }

    /**
     * 停止规则并取消预览范围内的未来待处理实例，包含已单独编辑的实例。
     *
     * @param id      规则 ID
     * @param request 变更预览返回的确认令牌
     * @param managed 是否以管理身份操作他人规则
     * @return 变更前后规则及被取消的实例列表
     * @throws TodoNotFoundException 规则不存在或无权访问
     * @throws TodoConflictException 令牌与当前状态不一致，需重新预览
     */
    @Audited(AuditOperation.TODO_SERIES_STOP)
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Mutation stop(long id, StopRequest request, boolean managed) {
        var before = accessible(repository.lock(id), managed);
        var plan = changes.plan(before, ChangeAction.STOP, null, true, managed);
        plan.confirm(request.token());
        Rule rule = before.rule();
        var stopped = progress(rule, Status.STOPPED, null, null, null, null);
        Instant now = now();
        repository.cancel(plan.preview().affected(), ChangeAction.STOP, TodoAccess.viewer(), now);
        repository.updateProgress(stopped, now);
        log.info("周期待办规则停止成功，规则 ID={}，取消实例数={}", id, plan.preview().affected().size());
        return new Mutation(rule, stopped, plan.preview().affected(), List.of());
    }

    /**
     * 供调度任务选取已到检查时间的活跃规则，按检查时间升序返回。
     */
    List<Long> due(int limit) {
        return repository.due(now(), limit);
    }

    /**
     * 生成失败后将检查时间延后固定间隔，避免调度任务立即重试。
     */
    void deferFailure(long id) {
        Instant now = now();
        repository.defer(id, now, now.plus(BLOCKED_DELAY));
        log.info("周期待办生成失败已延期重试，规则 ID={}，重试时间={}", id, now.plus(BLOCKED_DELAY));
    }

    /**
     * 由显式系统身份调用。锁定一条规则，在短事务内写入实例、游标和批次审计。
     * 可恢复的日历或负责人问题提交此前成功的期次并延迟重试，其他异常回滚整批。
     * 规则不存在、已停止或未到检查时间时幂等返回，不产生变更。
     *
     * @param id 规则 ID
     * @return 本批生成的实例 ID 与推进后的规则状态
     */
    @Audited(AuditOperation.TODO_SERIES_GENERATE)
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Mutation generate(long id) {
        var stored = repository.lock(id);
        Instant now = now();
        if (stored == null || stored.rule().status() != Status.ACTIVE
                || stored.rule().nextCheckAt() == null || stored.rule().nextCheckAt().isAfter(now)) {
            return Mutation.unchanged(stored == null ? null : stored.rule());
        }
        Rule rule = stored.rule();
        var batch = generateBatch(stored, now);
        repository.updateProgress(batch.after(), now);
        log.info("周期待办批次生成完成，规则 ID={}，生成数量={}，状态={}，下一锚点={}", id, batch.generated().size(),
                batch.after().status(), batch.after().nextBaseDate());
        return new Mutation(rule, batch.after(), List.of(), batch.generated());
    }

    private Batch generateBatch(Stored stored, Instant now) {
        Rule rule = stored.rule();
        Write definition = rule.rule();
        LocalDate last = rule.lastBaseDate();
        LocalDate next = rule.nextBaseDate();
        LocalDate horizon = todoSchedule.today().plusDays(WINDOW_DAYS);
        List<Long> generated = new ArrayList<>();
        try {
            checkGenerationInputs(stored);
            for (int count = 0; count <= BATCH_SIZE; count++) {
                next = schedule.next(definition, rule.effectiveFromDate(), last);
                if (next == null) {
                    return new Batch(progress(rule, Status.FINISHED, last, null, null, null), generated);
                }
                if (count == BATCH_SIZE) {
                    return new Batch(progress(rule, Status.ACTIVE, last, next, now, null), generated);
                }
                Occurrence occurrence = schedule.occurrence(definition, next);
                if (occurrence.occurrenceDate().isAfter(horizon)) {
                    Instant check = nextWindowCheck(definition, occurrence.occurrenceDate(), now);
                    return new Batch(progress(rule, Status.ACTIVE, last, next, check, null), generated);
                }
                if (!occurrence.occurrenceDate().isBefore(rule.effectiveFromDate())
                        && !repository.hasRetainedOccurrence(rule.id(), next)) {
                    var period = new TodoService.SeriesOccurrence(rule.id(), rule.ruleRevision(), next,
                            occurrence.occurrenceDate());
                    var created = todos.createSeriesOccurrence(period, rule.ownerUserId(), rule.ownerName(),
                            SeriesSchedule.todoWrite(definition, next, occurrence.dates()), occurrence.dates());
                    generated.add(created.id());
                }
                last = next;
                next = null;
            }
            throw new IllegalStateException("周期生成批次未推进");
        } catch (Blocked blocked) {
            log.info("周期待办生成受阻，规则 ID={}，原因={}，受阻日期={}", rule.id(), blocked.reason, blocked.date);
            return new Batch(progress(rule, Status.ACTIVE, last, next, now.plus(BLOCKED_DELAY), blocked), generated);
        }
    }

    private void checkGenerationInputs(Stored stored) {
        Rule rule = stored.rule();
        if (rule.rule().trading() && !Objects.equals(stored.marketItemId(),
                repository.activeMarket(rule.rule().marketCode()))) {
            throw new Blocked(BlockReason.MARKET_DISABLED, rule.nextBaseDate());
        }
        try {
            todos.requireActiveUser(rule.rule().assigneeUserId());
        } catch (TodoAssigneeException invalid) {
            throw new Blocked(BlockReason.ASSIGNEE_INVALID, rule.nextBaseDate());
        }
    }

    private static Instant nextWindowCheck(Write rule, LocalDate occurrenceDate, Instant now) {
        Instant entersWindow = occurrenceDate.minusDays(WINDOW_DAYS).atStartOfDay(WORKBENCH_ZONE).toInstant();
        Instant recheck = now.plus(TRADE_RECHECK);
        return rule.trading() && entersWindow.isAfter(recheck) ? recheck : entersWindow;
    }

    private Stored initialize(long id, Person owner, Definition definition, int revision, long version,
                              LocalDate effective, Instant now) {
        LocalDate first = schedule.next(definition.write(), effective, null);
        var rule = new Rule(id, owner.id(), owner.name(), definition.write(), first == null ? Status.FINISHED : Status.ACTIVE,
                revision, null, first, effective, first == null ? null : now, null, null, version);
        return new Stored(rule, definition.marketItemId());
    }

    private static Stored accessible(Stored stored, boolean managed) {
        if (stored == null || !managed && stored.rule().ownerUserId() != TodoAccess.viewer()) {
            throw new TodoNotFoundException();
        }
        return stored;
    }

    private static Rule progress(Rule rule, Status status, LocalDate last, LocalDate next, Instant check, Blocked blocked) {
        LocalDate blockedDate = blocked == null ? null : blocked.date;
        BlockReason reason = blocked == null ? null : blocked.reason;
        boolean changed = rule.status() != status || !Objects.equals(rule.lastBaseDate(), last)
                || !Objects.equals(rule.nextBaseDate(), next) || !Objects.equals(rule.blockedDate(), blockedDate)
                || rule.blockReason() != reason;
        return new Rule(rule.id(), rule.ownerUserId(), rule.ownerName(), rule.rule(), status, rule.ruleRevision(),
                last, next, rule.effectiveFromDate(), check, blockedDate, reason, rule.version() + (changed ? 1 : 0));
    }

    private Instant now() {
        return todoSchedule.now().toInstant(ZoneOffset.UTC);
    }

    /**
     * 规则变更结果。before 为 null 表示新建；generated 仅批次生成时非空。
     */
    public record Mutation(Rule before, Rule after, List<Affected> cancelled, List<Long> generated) {

        static Mutation unchanged(Rule rule) {
            return new Mutation(rule, rule, List.of(), List.of());
        }
    }

    private record Batch(Rule after, List<Long> generated) {

    }
}
