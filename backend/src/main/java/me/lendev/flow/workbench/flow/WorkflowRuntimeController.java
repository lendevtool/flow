package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.WorkflowRuntimeModels.*;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.document.DocumentCenter;
import me.lendev.flow.workbench.todo.TodoAccess;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 新版流程处理、附件及角色主备办入口。
 */
@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
@Tag(name = "角色流程运行")
class WorkflowRuntimeController {

    private final WorkflowRuntimeService service;
    private final WorkflowLifecycleService lifecycle;
    private final WorkflowQueries queries;
    private final WorkflowInstanceQueries instances;
    private final WorkflowPeople people;
    private final WorkflowBusinessDelivery delivery;

    /**
     * 管理端分页查询业务投递状态。
     */
    @GetMapping("/business-deliveries")
    R<Page<WorkflowBusinessDelivery.DeliveryInfo>> deliveries(
            @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = "PENDING|PROCESSING|DELIVERED|FAILED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        TodoAccess.permit(me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_FLOW_MANAGE);
        return R.ok(delivery.list(status, pageNo, pageSize));
    }

    /**
     * 管理端重试失败的投递事件。
     */
    @PostMapping("/business-deliveries/{eventId}/retry")
    R<WorkflowBusinessDelivery.DeliveryInfo> retry(@PathVariable long eventId) {
        TodoAccess.permit(me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_FLOW_MANAGE);
        return R.ok(delivery.retry(eventId));
    }

    /**
     * 发起角色流程，请求键幂等。
     */
    @PostMapping("/instances")
    R<Detail> start(@Valid @RequestBody Start request) {
        return R.ok(lifecycle.start(request));
    }

    /**
     * 按范围分页查询实例。
     */
    @GetMapping("/instances")
    R<InstancePage> list(@RequestParam(defaultValue = "MINE") Scope scope, @Valid InstanceFilters filters,
                         @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(instances.list(TodoAccess.viewer(), scope, filters, pageNo, pageSize));
    }

    /**
     * 查询实例详情。
     */
    @GetMapping("/instances/{id}")
    R<Detail> detail(@PathVariable long id) {
        return R.ok(queries.detail(id, TodoAccess.viewer()));
    }

    /**
     * 分页查询事件历史。
     */
    @GetMapping("/instances/{id}/history")
    R<Page<History>> history(@PathVariable long id, @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                             @RequestParam(defaultValue = "4") @Min(1) @Max(100) int pageSize) {
        return R.ok(queries.history(id, TodoAccess.viewer(), pageNo, pageSize));
    }

    /**
     * 执行运行操作：submit/reject/recall/cancel/retry-assignment。
     */
    @PostMapping("/instances/{id}/actions/{action}")
    R<Detail> act(@PathVariable long id, @PathVariable String action, @Valid @RequestBody Action request) {
        Operation operation;
        try {
            operation = Operation.valueOf(action.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BadRequestException.localized("error.flow.state");
        }
        return R.ok(service.act(id, TodoAccess.viewer(), operation, request));
    }

    /**
     * 上传任务附件并登记到指定字段。
     */
    @PostMapping(path = "/instances/{id}/tasks/{taskId}/attachments/{fieldCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    R<Attachment> upload(@PathVariable long id, @PathVariable long taskId, @PathVariable String fieldCode,
                         @RequestParam("file") MultipartFile file) throws IOException {
        int viewer = TodoAccess.viewer();
        try (var stream = file.getInputStream()) {
            return R.ok(service.upload(id, taskId, fieldCode, viewer, new DocumentCenter.Upload(
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), stream)));
        }
    }

    /**
     * 下载已批准的附件版本。
     */
    @GetMapping("/instances/{id}/attachments/{documentId}/{versionId}")
    void download(@PathVariable long id, @PathVariable long documentId, @PathVariable long versionId,
                  HttpServletResponse response) {
        service.download(id, documentId, versionId, TodoAccess.viewer(), (version, stream) -> {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLengthLong(version.sizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(version.originalFileName(), StandardCharsets.UTF_8).build().toString());
            stream.transferTo(response.getOutputStream());
        });
    }

    /**
     * 查询角色成员与主备配置，仅超级管理员。
     */
    @GetMapping("/roles/{roleId}/handlers")
    R<Handlers> handlers(@PathVariable int roleId) {
        return R.ok(people.handlers(roleId));
    }

    /**
     * 查询主备配置摘要及模板主办要求。
     */
    @GetMapping("/roles/{roleId}/handlers/summary")
    R<HandlerSummary> handlerSummary(@PathVariable int roleId) {
        return R.ok(people.handlerSummary(roleId));
    }

    /**
     * 替换角色主备办配置。
     */
    @PutMapping("/roles/{roleId}/handlers")
    R<Handlers> update(@PathVariable int roleId, @Valid @RequestBody HandlerWrite request) {
        return R.ok(people.update(roleId, request));
    }
}
