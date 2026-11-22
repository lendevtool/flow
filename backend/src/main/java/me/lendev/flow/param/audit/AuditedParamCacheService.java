package me.lendev.flow.param.audit;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.param.support.ParamLookupCache;
import org.springframework.stereotype.Service;

/**
 * 带审计的参数缓存刷新服务：单独包装一层，使刷新操作能被 {@code @Audited} 审计切面记录。
 */
@Service
@RequiredArgsConstructor
public class AuditedParamCacheService {

    private final ParamLookupCache cache;

    @Audited(AuditOperation.PARAM_CACHE_REFRESH)
    public void refresh() {
        cache.refresh();
    }
}
