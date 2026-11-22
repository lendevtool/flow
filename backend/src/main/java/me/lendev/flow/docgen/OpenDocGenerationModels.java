package me.lendev.flow.docgen;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 文档生成开放 API v1 的请求和公开响应。
 */
public final class OpenDocGenerationModels {
    private OpenDocGenerationModels() {
    }

    /**
     * 已启用且有当前发布版本的模板摘要。
     *
     * @param id               模板 ID，使用十进制字符串
     * @param code             稳定模板编码
     * @param name             模板显示名称
     * @param description      可空的模板用途说明
     * @param currentVersionId 当前发布版本 ID，使用十进制字符串
     */
    @Schema(name = "OpenDocGenerationTemplate")
    public record Template(String id, String code, String name, String description, String currentVersionId) {
    }

    /**
     * 用于构造生成请求的已发布模板配置。
     *
     * @param templateId  模板 ID，使用十进制字符串
     * @param name        模板显示名称
     * @param versionId   当前发布版本 ID，使用十进制字符串，提交生成时使用
     * @param versionNo   模板从 1 开始递增的业务版本号
     * @param inputSchema 此发布版本要求的 JSON Schema 对象
     * @param exampleJson 可空的默认输入对象
     * @param samples     此版本发布的具名输入样例，无配置时为空数组
     */
    @Schema(name = "OpenDocGenerationCurrentTemplate")
    public record CurrentTemplate(String templateId, String name, String versionId, int versionNo,
                                  @Schema(type = "object", description = "输入 JSON Schema") JsonNode inputSchema,
                                  @Schema(type = "object", nullable = true) JsonNode exampleJson,
                                  List<Sample> samples) {
    }

    /**
     * 模板在发布时保存的具名输入示例。
     *
     * @param code  同一模板版本内唯一的样例编码
     * @param name  样例显示名称
     * @param input 可复制后按需填写的完整输入对象
     */
    @Schema(name = "OpenDocGenerationSample")
    public record Sample(String code, String name, @Schema(type = "object") JsonNode input) {
    }

    /**
     * 使用指定模板版本生成一份 DOCX 的请求体。
     *
     * @param templateVersionId 必填，当前可用模板的发布版本 ID，使用正的十进制长整数字符串
     * @param input             必填，符合对应版本 JSON Schema 的根对象
     */
    @Schema(name = "OpenDocGenerationCreate")
    public record Create(
            @NotNull @Pattern(regexp = DocGenerationSupport.ID_PATTERN)
            @Schema(description = "当前发布的模板版本 ID", example = "102") String templateVersionId,
            @NotNull @JsonDeserialize(using = DocGenerationJson.class)
            @Schema(type = "object", description = "符合模板输入 Schema 的 JSON 对象") JsonNode input) {
    }

    /**
     * 应用自身的生成记录和成品可用状态。
     *
     * @param id                生成记录 ID，使用十进制字符串
     * @param templateId        所用模板的 ID，使用十进制字符串
     * @param templateVersionId 本次生成固定的版本 ID，使用十进制字符串
     * @param templateName      模板显示名称
     * @param versionNo         本次生成固定的业务版本号，从 1 开始
     * @param status            生成状态
     * @param errorCode         失败时的稳定错误码，运行中和成功时为空
     * @param createdAt         记录创建时间，ISO 8601 UTC 格式
     * @param finishedAt        成功或失败时间，ISO 8601 UTC 格式，运行时为空
     * @param hasFile           是否已有可下载的成品
     */
    @Schema(name = "OpenDocGenerationRecord")
    public record Generation(String id, String templateId, String templateVersionId, String templateName, int versionNo,
                             Status status, String errorCode, Instant createdAt, Instant finishedAt, boolean hasFile) {
    }

    /**
     * 记录状态。
     */
    public enum Status {
        /**
         * 记录已建立，正在渲染或保存成品。
         */
        RUNNING,
        /**
         * DOCX 成品已保存并与本记录绑定。
         */
        SUCCEEDED,
        /**
         * 生成已结束，原因由 errorCode 标识。
         */
        FAILED
    }
}
