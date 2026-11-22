package me.lendev.flow.docgen;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文档历史输入清理和超时记录恢复。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "document.generation", name = "maintenance-enabled", matchIfMissing = true)
class DocumentGenerationMaintenance {
    private final DocumentGenerationService generations;

    @Scheduled(initialDelayString = "${document.generation.maintenance-delay:60s}",
            fixedDelayString = "${document.generation.maintenance-delay:60s}")
    public void maintain() {
        generations.maintain();
    }
}
