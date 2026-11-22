package me.lendev.flow.audit.query;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.common.request.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 审计详情读取服务：包装查询方法，使"查看审计详情"这一动作本身也被记录审计。
 */
@Service
@RequiredArgsConstructor
public class AuditReadService {

    private final AuditQueryService queries;

    /**
     * 读取审计详情，查看动作本身以 {@code AUDIT_DETAIL_VIEW} 外部操作记录审计。
     *
     * @param id      审计日志主键
     * @param request 明细分页参数
     * @return 日志摘要与对象变更明细分页
     */
    @Audited(AuditOperation.AUDIT_DETAIL_VIEW)
    public Map<String, Object> detail(Long id, PageRequest request) {
        return queries.detail(id, request);
    }
}
