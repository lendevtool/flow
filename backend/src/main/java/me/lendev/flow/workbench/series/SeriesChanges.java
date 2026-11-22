package me.lendev.flow.workbench.series;

import static me.lendev.flow.workbench.series.SeriesDefinitions.invalid;
import static me.lendev.flow.workbench.series.SeriesModels.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.workbench.series.SeriesDefinitions.Definition;
import me.lendev.flow.workbench.series.SeriesRepository.Stored;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoSchedule;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 计算周期规则变更的影响范围，并将规则和实例版本绑定到确认令牌。
 */
@Component
@RequiredArgsConstructor
@Slf4j
class SeriesChanges {

    private static final String PREVIEW_CHANGED = "error.todo.series.conflict";

    private final SeriesRepository repository;
    private final SeriesSchedule schedule;
    private final SeriesDefinitions definitions;
    private final TodoSchedule todoSchedule;
    private final ObjectMapper json;

    /**
     * 提交变更时加锁读取受影响的实例，在事务内重新计算预览供确认。
     *
     * @param stored  已读取（提交时已加锁）的规则
     * @param action  变更动作，EDIT 必须携带新规则，STOP 必须为空
     * @param request 新规则内容，STOP 时为 null
     * @param lock    是否锁定受影响实例，预览阶段为 false
     * @param managed 是否以管理身份操作他人规则
     * @return 变更计划，含确认令牌与规范化后的规则定义
     * @throws TodoConflictException 规则已停止仍尝试加锁变更
     */
    Plan plan(Stored stored, ChangeAction action, Write request, boolean lock, boolean managed) {
        Rule rule = stored.rule();
        if (rule.status() == Status.STOPPED && lock) {
            log.info("周期规则已停止，拒绝变更提交，规则 ID={}", rule.id());
            throw new TodoConflictException(PREVIEW_CHANGED);
        }
        if (rule.status() == Status.STOPPED || (action == ChangeAction.EDIT) != (request != null)) {
            throw invalid();
        }
        LocalDate today = todoSchedule.today();
        Definition replacement = request == null ? null : definitions.normalize(request, rule.rule(), managed);
        boolean unchanged = replacement != null && replacement.write().equals(rule.rule());
        List<Affected> affected = unchanged ? List.of() : repository.affected(rule.id(), today, action, lock);
        Preview schedulePreview = replacement == null ? null : preview(replacement, today.plusDays(1));
        var fingerprint = new Fingerprint(rule.id(), rule.version(), today, action, replacement, affected, schedulePreview);
        return new Plan(new ChangePreview(fingerprint(fingerprint), today, affected, schedulePreview), replacement);
    }

    /**
     * 从指定生效日起预览前三期，并附带交易日历覆盖范围。
     */
    Preview preview(Definition definition, LocalDate effective) {
        return schedule.preview(definition.write(), effective, repository.coverage(definition.marketItemId()));
    }

    private String fingerprint(Fingerprint state) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(state));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 变更计划。确认令牌绑定规则版本、业务日期、请求内容与受影响实例版本。
     */
    record Plan(ChangePreview preview, Definition definition) {

        /**
         * 校验提交令牌与预览令牌一致，不一致说明期间状态已变化，需重新预览。
         *
         * @throws TodoConflictException 令牌不匹配
         */
        void confirm(String token) {
            if (!preview.token().equals(token)) {
                log.info("周期规则变更确认令牌不匹配，预览已过期，业务日期={}", preview.cutoffDate());
                throw new TodoConflictException(PREVIEW_CHANGED);
            }
        }
    }

    private record Fingerprint(long id, long version, LocalDate today, ChangeAction action, Definition definition,
                               List<Affected> affected, Preview schedule) {

    }
}
