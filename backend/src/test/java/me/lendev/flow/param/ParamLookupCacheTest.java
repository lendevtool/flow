package me.lendev.flow.param;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import me.lendev.flow.param.domain.ParamGroup;
import me.lendev.flow.param.support.ParamLookupCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class ParamLookupCacheTest {

    private final ObjectMapper json = new ObjectMapper();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> storage = new HashMap<>();
    private final ParamLookupCache cache = new ParamLookupCache(redis, json);

    @BeforeEach
    void setup() {
        // 用内存 Map 模拟 Redis 的 get/set/increment 行为，使测试可以对真实写入的键值做断言。
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> storage.get(call.getArgument(0)));
        doAnswer(call -> {
            storage.put(call.getArgument(0), call.getArgument(1));
            return null;
        })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.increment(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            long next = Long.parseLong(storage.getOrDefault(key, "0")) + 1;
            storage.put(key, String.valueOf(next));
            return next;
        });
    }

    private ParamGroup group(long version) {
        ParamGroup group = new ParamGroup();
        group.setId(123L);
        group.setVersion(version);
        return group;
    }

    @Test
    void cachesReadsAndDatabaseRevisionInvalidatesAfterWrite() {
        AtomicInteger loads = new AtomicInteger();
        var first = cache.get(group(0), () -> {
            loads.incrementAndGet();
            return json.readTree("[1]");
        });
        var cached = cache.get(group(0), () -> fail("应使用缓存"));
        assertEquals(first, cached);
        assertEquals(1, loads.get());
        assertEquals(json.readTree("[2]"), cache.get(group(1), () -> json.readTree("[2]")));
        verify(values, times(2)).set(anyString(), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void staleReaderCannotReplaceTheNewRevision() {
        // 旧版本读取的加载回调执行期间，新版本已写入缓存；
        // 旧读者的结果不得覆盖新修订（模拟并发读写下的版本保护）。
        cache.get(group(0), () -> {
            cache.get(group(1), () -> json.readTree("[2]"));
            return json.readTree("[1]");
        });
        assertEquals(json.readTree("[2]"), cache.get(group(1), () -> fail("旧读不能覆盖新版本")));
    }

    @Test
    void transactionalReadsDoNotPublishUncommittedData() {
        // 事务激活时绕过缓存直读数据库，避免把未提交数据发布给其他请求。
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals(json.readTree("[99]"), cache.get(group(0), () -> json.readTree("[99]")));
            verifyNoInteractions(redis);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertEquals(json.readTree("[1]"), cache.get(group(0), () -> json.readTree("[1]")));
    }

    @Test
    void unavailableRedisFallsBackToDatabase() {
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(json.readTree("[1]"), cache.get(group(0), () -> json.readTree("[1]")));
    }

    @Test
    void failedBackfillReturnsTheLoadedResultWithoutLoadingAgain() {
        doThrow(new DataAccessResourceFailureException("offline"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        var loads = new AtomicInteger();
        var expected = json.readTree("[1]");
        assertEquals(expected, cache.get(group(0), () -> {
            loads.incrementAndGet();
            return expected;
        }));
        assertEquals(1, loads.get());
    }

    @Test
    void databaseFailurePropagatesWithoutRetry() {
        var databaseFailure = new DataAccessResourceFailureException("database offline");
        var loads = new AtomicInteger();
        var group = group(0);
        assertSame(databaseFailure, assertThrows(DataAccessResourceFailureException.class, () -> cache.get(group, () -> {
            loads.incrementAndGet();
            throw databaseFailure;
        })));
        assertEquals(1, loads.get());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void refreshChangesTheCacheNamespace() {
        cache.get(group(0), () -> json.readTree("[1]"));
        cache.refresh();
        assertEquals(json.readTree("[2]"), cache.get(group(0), () -> json.readTree("[2]")));
    }
}
