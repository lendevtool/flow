package me.lendev.flow.param.support;

import java.time.Duration;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.param.domain.ParamGroup;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 参数选项的 Redis 缓存，使用全局代次和参数组版本隔离不同版本的结果。
 * 数据变更与参数组版本递增在同一数据库事务完成。持有旧版本的查询即使稍后回填，也只写入旧版本的缓存键。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParamLookupCache {

    private static final String EPOCH = "flow:param:cache:epoch";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    /**
     * 读取参数组选项。事务内直接调用加载回调，避免把未提交的数据回填到共享缓存。
     * 事务外优先使用 Redis，缓存不可用时回退到数据库。
     *
     * @param group  参数组，其 id 与 version 参与缓存 key 计算
     * @param loader 缓存未命中时从数据库加载数据的回调
     * @return 参数查找结果 JSON
     */
    public JsonNode get(ParamGroup group, Supplier<JsonNode> loader) {
        // 事务内直接读库：避免把未提交的行写入 Redis，造成其他连接读到脏数据。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return loader.get();
        }
        String key;
        try {
            String epoch = redis.opsForValue().get(EPOCH);
            key = "flow:param:lookup:v2:" + (epoch == null ? "0" : epoch)
                    + ":" + group.getId() + ":" + group.getVersion();
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return json.readTree(cached);
            }
        } catch (DataAccessException ex) {
            log.warn("参数选项缓存读取失败，本次回退数据库，请检查 Redis 连通性并关注数据库负载，requestId={} groupId={} groupVersion={} errorType={}",
                    AuditParticipants.currentRequestId(), group.getId(), group.getVersion(), ex.getClass().getSimpleName());
            return loader.get();
        }
        JsonNode result = loader.get();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(result), Duration.ofMinutes(5));
        } catch (DataAccessException ex) {
            log.warn("参数选项缓存回填失败，已加载结果仍可返回，请检查 Redis 可写状态、内存和权限，requestId={} groupId={} groupVersion={} errorType={}",
                    AuditParticipants.currentRequestId(), group.getId(), group.getVersion(), ex.getClass().getSimpleName());
        }
        return result;
    }

    /**
     * 递增全局代次，后续查询使用新缓存键，旧键按各自的有效期过期。
     *
     * @throws DataAccessException Redis 未能完成刷新
     */
    public void refresh() {
        Long epoch = redis.opsForValue().increment(EPOCH);
        log.info("参数选项缓存代次已更新，requestId={} epoch={}", AuditParticipants.currentRequestId(), epoch);
    }
}
