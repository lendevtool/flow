package me.lendev.flow.openapi.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import me.lendev.flow.common.response.Page;

/**
 * 管理配置共用数据库 UTC 时间、分页响应和版本检查。
 */
final class OpenApiAdminMapperSupport {

    private OpenApiAdminMapperSupport() {
    }

    static LocalDateTime now(Clock clock) {
        return utc(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    static void requireVersion(Long version, Long expected) {
        if (!Objects.equals(version, expected)) {
            throw OpenApiAdminFailure.versionConflict();
        }
    }

    static void requireWrite(int count) {
        if (count != 1) {
            throw OpenApiAdminFailure.versionConflict();
        }
    }

    static <T> Page<T> page(List<T> rows, long total) {
        Page<T> page = new Page<>();
        page.setPageData(rows);
        page.setTotal(total);
        return page;
    }
}
