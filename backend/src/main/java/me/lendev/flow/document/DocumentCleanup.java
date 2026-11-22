package me.lendev.flow.document;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditSystemContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫描删除队列和中断的上传，按数据库租约回收 SFTP 文件。
 */
@Component
@ConditionalOnProperty(prefix = "document.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class DocumentCleanup {

    private final DocumentRepository repository;
    private final DocumentMutations mutations;
    private final DocumentTransfers transfers;
    private final Clock clock;

    /**
     * 回收待删除版本和超时的未完成上传。领取租约后才删除 SFTP 文件，单条失败留给后续扫描。
     */
    @Scheduled(fixedDelayString = "${document.cleanup.delay:60000}", initialDelayString = "${document.cleanup.delay:60000}",
            scheduler = "documentCleanupScheduler")
    void cleanup() {
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var retryBefore = now.minusMinutes(2);
        var staleBefore = now.minusHours(24);
        try (var identity = AuditSystemContext.open("文档中心文件清理")) {
            for (var candidate : repository.cleanupCandidates(retryBefore, staleBefore)) {
                try {
                    var job = mutations.claimCleanup(candidate.getDocumentId(), candidate.getId(), retryBefore, staleBefore);
                    if (job != null) {
                        transfers.cleanup(job.getDocumentId(), job.getId(), job.getCleanupAuditOperationId(), job);
                        mutations.cleaned(job.getDocumentId(), job.getId(), job.getUpdatedAt());
                    }
                } catch (RuntimeException failure) {
                    log.warn("文档清理未完成，后续扫描将重试，请检查版本状态写入和 SFTP 删除权限，documentId={} versionId={} operationId={} status={} errorType={}",
                            candidate.getDocumentId(), candidate.getId(), candidate.getCleanupAuditOperationId(),
                            candidate.getStatus(), failure.getClass().getSimpleName());
                }
            }
        }
    }
}
