package me.lendev.flow.workbench.series;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditSystemContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按到期顺序选取有限规则，每条规则使用独立事务和显式系统身份。
 */
@Component
@ConditionalOnProperty(name = "workbench.todo-series.worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class SeriesWorker {

    private static final int RULES_PER_SCAN = 32;
    private final SeriesService service;

    /**
     * 定时扫描到期规则并逐条生成，单条失败不影响其余规则。
     */
    @Scheduled(fixedDelayString = "${workbench.todo-series.poll-ms:30000}",
            initialDelayString = "${workbench.todo-series.poll-ms:30000}")
    void tick() {
        try (var identity = AuditSystemContext.open("周期待办生成任务")) {
            var due = service.due(RULES_PER_SCAN);
            if (!due.isEmpty()) {
                log.info("周期待办生成任务开始，到期规则数={}", due.size());
            }
            for (long id : due) {
                generate(id);
            }
        }
    }

    private void generate(long id) {
        try {
            service.generate(id);
        } catch (RuntimeException failure) {
            try {
                service.deferFailure(id);
                log.warn("周期待办生成失败，已请求延期重试，请检查周期规则、负责人及待办写入，seriesId={} errorType={}",
                        id, failure.getClass().getSimpleName());
            } catch (RuntimeException unavailable) {
                log.warn("周期待办生成及重试延期均失败，规则可能在下轮再次被扫描，请检查规则表和审计库写入，seriesId={} generationErrorType={} deferErrorType={}",
                        id, failure.getClass().getSimpleName(), unavailable.getClass().getSimpleName());
            }
        }
    }
}
