package me.lendev.flow.audit.query;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计队列人工重试服务：在数据库审计事务内按乐观版本号重置 outbox 记录状态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditAdminService {

    private final AuditStore store;

    /**
     * 人工重试一条 RETRY/DEAD 队列记录，重试动作本身也被审计。
     *
     * @param id      outbox 记录主键
     * @param request 携带乐观锁版本号的重试请求
     * @throws BadRequestException 版本过期或状态不允许重试时抛出
     */
    @Audited(AuditOperation.AUDIT_RETRY)
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long id, AuditRetryRequest request) {
        String actorId = StpUtil.getLoginIdAsString();
        if (!store.retry(id, request.getRowVersion(), actorId)) {
            log.info("人工重试版本冲突，队列ID={} rowVersion={}", id, request.getRowVersion());
            throw BadRequestException.localized("error.audit.queue.retry.stale");
        }
        log.info("人工重试审计投递成功，队列ID={} rowVersion={} 操作者ID={}", id, request.getRowVersion(), actorId);
    }
}
