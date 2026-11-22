package me.lendev.flow.docgen;

import static me.lendev.flow.docgen.DocGenerationSupport.DOCX_TYPE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.docgen.DocGenerationModels.Actor;
import me.lendev.flow.docgen.OpenDocGenerationModels.Create;
import me.lendev.flow.docgen.OpenDocGenerationModels.CurrentTemplate;
import me.lendev.flow.docgen.OpenDocGenerationModels.Generation;
import me.lendev.flow.docgen.OpenDocGenerationModels.Sample;
import me.lendev.flow.docgen.OpenDocGenerationModels.Status;
import me.lendev.flow.docgen.OpenDocGenerationModels.Template;
import me.lendev.flow.openapi.OpenApiEndpoint;
import me.lendev.flow.openapi.OpenApiException;
import me.lendev.flow.openapi.OpenApiRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 以应用身份提供已发布的文档模板及生成、查询和下载入口。
 *
 * <p>调用方使用应用 Bearer Token。开放 API 拦截器校验接口发布状态、应用 Scope 和配额。
 * 生成记录以通过校验的应用为所有者。JSON 成功响应直接返回公开 DTO，下载返回 DOCX 附件。
 * 错误按开放 API 约定返回 HTTP 状态及 {@code code}、{@code message} 和 {@code requestId}。
 * 所有响应禁止缓存。
 */
@RestController
@RequestMapping(value = "/open/v1/document-generation", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "文档生成开放接口")
@SecurityRequirement(name = "appBearer")
public class OpenDocGenerationController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}";
    private final DocumentGenerationService generations;

    /**
     * 查询当前启用且已发布的模板。
     *
     * @return 按模板 ID 升序排列的模板 ID、编码、名称、说明和当前发布版本 ID，无可用项时返回空数组
     */
    @GetMapping("/templates")
    @Operation(summary = "文档生成模板列表")
    @OpenApiEndpoint(value = "document-generation.templates.read", dataScopeType = "NONE")
    public List<Template> templates() {
        return business(generations::availableTemplates).stream().map(template -> new Template(template.id(),
                template.code(), template.name(), template.description(), template.currentVersionId())).toList();
    }

    /**
     * 查询一个启用模板的当前发布版本和输入配置。
     *
     * @param id 必填路径参数，模板的正整数 ID
     * @return 模板和版本标识、版本号、输入 Schema、可空的默认示例和具名样例数组
     * @throws OpenApiException 参数无效返回 {@code 400 INVALID_ARGUMENT}，模板不可用返回 {@code 404 TEMPLATE_NOT_FOUND}
     */
    @GetMapping("/templates/{id}/current")
    @Operation(summary = "文档生成当前模板", description = "返回当前发布版本的输入 Schema、可选默认输入和样例。")
    @OpenApiEndpoint(value = "document-generation.templates.current.read", dataScopeType = "NONE")
    public CurrentTemplate current(@PathVariable @Positive long id) {
        var current = business(() -> generations.availableTemplate(id));
        var samples = current.samples();
        var examples = samples == null ? List.<Sample>of() : samples.valueStream()
                .map(sample -> new Sample(sample.path("code").asString(), sample.path("name").asString(), sample.path("input")))
                .toList();
        return new CurrentTemplate(current.templateId(), current.name(), current.versionId(), current.versionNo(),
                current.inputSchema(), current.exampleJson(), examples);
    }

    /**
     * 以当前授权应用为所有者提交一份生成请求，重复提交时沿用该应用的记录。
     *
     * @param requestKey 必填 {@code Idempotency-Key} 请求头，使用 36 字符 UUID，同一请求的重试使用同一值
     * @param body       必填 JSON 请求体，包含当前发布版本 ID 及符合版本 Schema 的根对象
     * @param request    当前已通过应用鉴权的 HTTP 请求，用于读取受信任的应用身份
     * @return 已完成的记录及 200，已有记录仍在运行时返回该记录及 202
     * @throws OpenApiException 请求协议或输入无效返回 {@code 400 INVALID_ARGUMENT} 或 {@code 400 INVALID_INPUT}，
     *                          版本不可用返回 {@code 404 TEMPLATE_NOT_FOUND}，版本已切换或重复键对应另一请求返回
     *                          {@code 409 TEMPLATE_VERSION_CHANGED} 或 {@code 409 REQUEST_KEY_CONFLICT}，
     *                          生成配额已满返回 {@code 429 GENERATION_BUSY}
     */
    @PostMapping(value = "/generations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "发起文档生成", description = "同一应用以同一幂等键、版本和输入重试时返回原记录。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "记录已完成",
                            content = @Content(schema = @Schema(implementation = Generation.class))),
                    @ApiResponse(responseCode = "202", description = "同一请求的原记录仍在运行",
                            content = @Content(schema = @Schema(implementation = Generation.class))),
                    @ApiResponse(responseCode = "409", description = "TEMPLATE_VERSION_CHANGED 或 REQUEST_KEY_CONFLICT",
                            content = @Content(schema = @Schema(ref = "#/components/schemas/OpenApiBusinessError"))),
                    @ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE",
                            content = @Content(schema = @Schema(ref = "#/components/schemas/OpenApiBusinessError"))),
                    @ApiResponse(responseCode = "422", description = "INVALID_TEMPLATE 或 INVALID_RENDER_DATA",
                            content = @Content(schema = @Schema(ref = "#/components/schemas/OpenApiBusinessError")))
            })
    @OpenApiEndpoint(value = "document-generation.generations.create", dataScopeType = "NONE")
    public ResponseEntity<Generation> generate(
            @RequestHeader(IDEMPOTENCY_KEY)
            @Parameter(description = "应用内唯一的 UUID。网络重试时保持同一值。", required = true,
                    schema = @Schema(type = "string", format = "uuid"))
            @Pattern(regexp = UUID_PATTERN) String requestKey,
            @RequestBody @Valid Create body, HttpServletRequest request) {
        Actor actor = actor(request);
        long templateVersionId = versionId(body.templateVersionId());
        var result = generation(business(() -> generations.generate(actor, requestKey, templateVersionId, body.input())));
        return result.status() == Status.RUNNING ? ResponseEntity.accepted().body(result) : ResponseEntity.ok(result);
    }

    /**
     * 查询当前应用的一个生成记录。
     *
     * @param id      必填路径参数，生成记录的正整数 ID
     * @param request 当前已通过应用鉴权的 HTTP 请求，用于读取受信任的应用身份
     * @return 记录 ID、模板和版本、状态及可空的错误码和结束时间，以及成品可用状态
     * @throws OpenApiException 参数无效返回 {@code 400 INVALID_ARGUMENT}，
     *                          记录不存在或归属其他主体返回 {@code 404 GENERATION_NOT_FOUND}
     */
    @GetMapping("/generations/{id}")
    @Operation(summary = "文档生成记录详情")
    @OpenApiEndpoint(value = "document-generation.generations.read", dataScopeType = "NONE")
    public Generation detail(@PathVariable @Positive long id, HttpServletRequest request) {
        Actor actor = actor(request);
        return generation(business(() -> generations.generation(actor, id)));
    }

    /**
     * 流式写出当前应用生成记录固定的 DOCX 文件。
     *
     * @param id       必填路径参数，生成记录的正整数 ID
     * @param request  当前已通过应用鉴权的 HTTP 请求，用于读取受信任的应用身份
     * @param response 写出文件媒体类型、UTF-8 附件名和文档内容的 HTTP 响应
     * @throws OpenApiException 参数无效返回 {@code 400 INVALID_ARGUMENT}，记录不存在或归属其他主体返回
     *                          {@code 404 GENERATION_NOT_FOUND}，当前记录没有成品返回 {@code 409 GENERATION_FILE_UNAVAILABLE}，
     *                          响应提交前存储不可用返回 {@code 503 STORAGE_UNAVAILABLE}
     * @throws IOException      开始传输后下载中断
     */
    @GetMapping(value = "/generations/{id}/file", produces = DOCX_TYPE)
    @Operation(summary = "文档生成成品下载", description = "返回当前应用生成记录固定的 DOCX 附件。请求的 Accept 可使用 DOCX 媒体类型。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "DOCX 文件",
                            headers = @Header(name = HttpHeaders.CONTENT_DISPOSITION, description = "UTF-8 文件名"),
                            content = @Content(mediaType = DOCX_TYPE, schema = @Schema(type = "string", format = "binary"))),
                    @ApiResponse(responseCode = "409", description = "GENERATION_FILE_UNAVAILABLE",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(ref = "#/components/schemas/OpenApiBusinessError")))
            })
    @OpenApiEndpoint(value = "document-generation.generations.file.read", dataScopeType = "NONE")
    public void download(@PathVariable @Positive long id, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        try {
            generations.download(actor(request), id, (version, stream) -> {
                response.setContentType(DOCX_TYPE);
                response.setContentLengthLong(version.sizeBytes());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(version.downloadName(), StandardCharsets.UTF_8).build().toString());
                stream.transferTo(response.getOutputStream());
                response.flushBuffer();
            });
        } catch (DocGenerationFailure failure) {
            if (response.isCommitted()) {
                throw new IOException("DOCX 下载传输未完成", failure);
            }
            response.resetBuffer();
            response.setContentType(null);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, null);
            response.setHeader(HttpHeaders.CONTENT_LENGTH, null);
            throw openError(failure);
        }
    }

    private static Actor actor(HttpServletRequest request) {
        return Actor.app(OpenApiRequest.requireCurrent(request).appId());
    }

    private static long versionId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new OpenApiException(400, "INVALID_ARGUMENT", "模板版本 ID 超出范围");
        }
    }

    private static Generation generation(DocGenerationModels.GenerationView view) {
        return new Generation(view.id(), view.templateId(), view.templateVersionId(), view.templateName(),
                view.versionNo(), Status.valueOf(view.status()), view.errorCode(), view.createdAt(),
                view.finishedAt(), view.hasFile());
    }

    private static <T> T business(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DocGenerationFailure failure) {
            throw openError(failure);
        }
    }

    private static OpenApiException openError(DocGenerationFailure failure) {
        return new OpenApiException(failure.status().value(), failure.code(), failure.getMessage());
    }
}
