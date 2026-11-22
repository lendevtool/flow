package me.lendev.flow.audit.query;

import jakarta.validation.constraints.*;

import java.time.Instant;

import lombok.Data;
import lombok.EqualsAndHashCode;
import me.lendev.flow.common.request.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 审计日志查询请求参数：所有过滤条件均带长度或格式约束，防止非法输入进入 SQL 拼接层。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditQueryRequest extends PageRequest {

    /**
     * 查询起始时间（ISO-8601），省略时默认为结束时间前 7 天。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant startTime;
    /**
     * 查询结束时间（ISO-8601），省略时默认为当前时间；起止跨度不得超过 90 天。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant endTime;
    /**
     * 操作者关键字，模糊匹配姓名、认证账号与声称账号，通配符已被转义。
     */
    @Size(max = 100)
    private String actorKeyword;
    /**
     * 操作者标识精确匹配。
     */
    @Size(max = 128)
    private String actorId;
    /**
     * 模块编码精确匹配，仅限大写字母与下划线。
     */
    @Pattern(regexp = "[A-Z_]{1,32}")
    private String moduleCode;
    /**
     * 操作编码精确匹配。
     */
    @Size(max = 96)
    private String operationCode;
    /**
     * 操作结果精确匹配。
     */
    @Pattern(regexp = "SUCCESS|FAILURE|PARTIAL|NO_CHANGE|UNKNOWN")
    private String result;
    /**
     * 对象类型精确匹配，需配合对象条件触发 EXISTS 子查询。
     */
    @Size(max = 64)
    private String objectType;
    /**
     * 对象标识精确匹配。
     */
    @Size(max = 128)
    private String objectId;
    /**
     * 对象编码精确匹配。
     */
    @Size(max = 128)
    private String objectCode;
    /**
     * 父对象类型精确匹配。
     */
    @Size(max = 64)
    private String parentType;
    /**
     * 父对象标识精确匹配。
     */
    @Size(max = 128)
    private String parentId;
    /**
     * HTTP 请求 ID 精确匹配，用于关联应用日志。
     */
    @Size(max = 64)
    private String requestId;
    /**
     * 审计操作 ID 精确匹配，用于关联同一次操作的多个事件。
     */
    @Size(max = 36)
    private String operationId;
}
