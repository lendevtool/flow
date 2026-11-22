package me.lendev.flow.docgen;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.docgen.render.RenderFailure;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class DocGenerationSupport {
    static final String ID_PATTERN = "[1-9]\\d{0,18}";
    static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String DRAFT = "DRAFT";
    static final String PUBLISHED = "PUBLISHED";
    static final String RUNNING = "RUNNING";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";

    private DocGenerationSupport() {
    }

    static long id(String value) {
        try {
            if (value != null && value.matches(ID_PATTERN)) return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // 超出 BIGINT 范围，与其他非法标识返回相同错误。
        }
        throw DocGenerationFailure.invalid("文档生成标识必须为正整数");
    }

    static String id(Long value) {
        return value == null ? null : value.toString();
    }

    static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime now(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    static LocalDateTime nextTime(Clock clock, LocalDateTime previous) {
        var current = now(clock);
        return current.isAfter(previous) ? current : previous.plusNanos(1_000_000);
    }

    static JsonNode nullable(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    static String text(String value, int max, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (!required) return null;
            throw DocGenerationFailure.invalid("请填写" + field);
        }
        String normalized = value.strip();
        if (normalized.length() > max || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw DocGenerationFailure.invalid(field + "长度或字符无效");
        }
        return normalized;
    }

    static void page(int number, int size) {
        if (number < 1 || size < 1 || size > 100) throw DocGenerationFailure.invalid("分页参数无效");
    }

    static DocGenerationFailure renderFailure(RenderFailure error, boolean template) {
        HttpStatus status = switch (error.code()) {
            case "FILE_TOO_LARGE" -> HttpStatus.CONTENT_TOO_LARGE;
            case "UNSUPPORTED_FILE_TYPE" -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case "INVALID_INPUT" -> template ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        String code = template && !error.code().equals("FILE_TOO_LARGE") && !error.code().equals("UNSUPPORTED_FILE_TYPE")
                ? "INVALID_TEMPLATE" : error.code();
        String message = switch (status) {
            case CONTENT_TOO_LARGE -> "文件超过大小限制";
            case UNSUPPORTED_MEDIA_TYPE -> "只接受有效的 DOCX 文件";
            default -> template ? "模板文件、渲染配置或样例无效" : "输入不符合模板规则";
        };
        return new DocGenerationFailure(status, code, message, error.path());
    }

    static DocGenerationFailure unavailable() {
        return new DocGenerationFailure(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "文档存储暂不可用");
    }

    static <T> T system(String operation, Supplier<T> action) {
        try (var scope = AuditSystemContext.open(operation)) {
            return action.get();
        }
    }
}
