package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.exception.BadRequestException.localized;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.document.DocumentCenter;
import me.lendev.flow.field.FieldModels.DataType;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Attachment;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import me.lendev.flow.workbench.flow.entity.WorkflowUpload;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 流程附件仅存引用。文档中心负责文件传输及版本锁定。
 */
@Component
@RequiredArgsConstructor
@Slf4j
class WorkflowAttachments {

    private final WorkflowRuntimeRepository repository;
    private final DocumentCenter documents;
    private final WorkflowPeople people;
    private final WorkflowBusinessRegistry modules;

    /**
     * 上传文件到文档中心，此时尚未绑定流程业务记录。
     */
    DocumentCenter.Version upload(DocumentCenter.Upload input) {
        return documents.upload(null, null, new DocumentCenter.Metadata(input.originalFileName(), null, null, Map.of()), input);
    }

    /**
     * 登记上传记录并关联流程、任务与字段，提交时才正式绑定文档。
     */
    @Transactional(rollbackFor = Exception.class)
    public Attachment register(long flowId, long taskId, String field, int actor, DocumentCenter.Version uploaded) {
        var row = new WorkflowUpload();
        row.setFlowInstanceId(flowId);
        row.setNodeTaskId(taskId);
        row.setFieldCode(field);
        row.setUploaderUserId(actor);
        row.setDocumentId(uploaded.documentId());
        row.setVersionId(uploaded.id());
        row.setCreatedAt(people.now());
        repository.insert(row);
        log.info("流程附件登记，instanceId={}，taskId={}，字段={}，documentId={}，versionId={}", flowId, taskId,
                field, uploaded.documentId(), uploaded.id());
        return new Attachment(Long.toString(uploaded.documentId()), Long.toString(uploaded.id()),
                uploaded.originalFileName(), uploaded.sizeBytes());
    }

    /**
     * 校验字段为当前节点可编辑的附件字段。
     *
     * @throws me.lendev.flow.common.exception.BadRequestException 字段不可编辑或不是附件类型
     */
    void verifyField(WorkflowRun flow, WorkflowTask task, String code) {
        var node = flow.node(flow.attempt(task.getNodeInstanceId()).getNodeKey());
        if (!modules.editableFields(flow, node).contains(code) || flow.definition().fields().stream()
                .noneMatch(f -> f.code().equals(code) && f.config().dataType() == DataType.ATTACHMENT)) {
            throw localized("error.workflow.fields");
        }
    }

    /**
     * 提取字段值中的非空附件引用，用于任务处理留痕。
     */
    Map<String, JsonNode> snapshot(WorkflowRun flow, Map<String, JsonNode> values) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var field : flow.definition().fields()) {
            var value = values.get(field.code());
            if (field.config().dataType() == DataType.ATTACHMENT && value != null && value.isArray() && !value.isEmpty()) {
                result.put(field.code(), value);
            }
        }
        return result;
    }

    /**
     * 提交时绑定新增附件：上传记录须属于本任务、本人、同字段且未绑定，
     * 否则视为冲突。已存在于当前值的引用跳过。
     *
     * @throws TodoConflictException 上传记录不匹配或已被绑定
     * @throws TodoNotFoundException 上传记录不存在
     */
    void bind(WorkflowRun flow, WorkflowTask task, Map<String, JsonNode> current, Map<String, JsonNode> patch) {
        long id = flow.row().getId();
        for (var entry : snapshot(flow, patch).entrySet()) {
            for (var ref : entry.getValue()) {
                long documentId = documentId(ref);
                long versionId = versionId(ref);
                if (contains(current.get(entry.getKey()), documentId, versionId)) {
                    continue;
                }
                var upload = repository.upload(id, versionId).orElseThrow(TodoNotFoundException::new);
                if (upload.getDocumentId() != documentId || !Objects.equals(upload.getNodeTaskId(), task.getId())
                        || !Objects.equals(upload.getUploaderUserId(), task.getAssigneeUserId())
                        || !upload.getFieldCode().equals(entry.getKey()) || upload.getBoundAt() != null) {
                    log.info("流程附件绑定冲突，instanceId={}，documentId={}，versionId={}", id, documentId, versionId);
                    throw new TodoConflictException("error.workflow.attachment");
                }
                documents.bind(business(id), documentId, versionId);
                upload.setBoundAt(people.now());
                repository.update(upload);
                log.info("流程附件绑定，instanceId={}，documentId={}，versionId={}", id, documentId, versionId);
            }
        }
    }

    /**
     * 查询附件快照中各版本的原始文件名，版本缺失时回退文档名。
     */
    Map<String, String> names(long id, Map<String, JsonNode> snapshot) {
        var result = new LinkedHashMap<String, String>();
        for (var values : snapshot.values()) {
            for (var ref : values) {
                long documentId = documentId(ref);
                String key = ref.path("versionId").asString();
                result.computeIfAbsent(key, ignored -> {
                    var document = documents.detail(business(id), documentId);
                    return document.versions().stream().filter(version -> version.id() == versionId(ref))
                            .map(DocumentCenter.Version::originalFileName).findFirst().orElse(document.name());
                });
            }
        }
        return result;
    }

    /**
     * 下载附件：仅允许当前字段值或已处理任务快照中出现过的版本。
     *
     * @throws TodoNotFoundException 版本不在任何已批准快照中
     */
    void download(WorkflowRun flow, long documentId, long versionId, DocumentCenter.Download output) {
        long id = flow.row().getId();
        var current = snapshot(flow, repository.values(flow.row().getFieldValuesJson()));
        var approved = current.values().stream().anyMatch(array -> contains(array, documentId, versionId))
                || flow.tasks().stream().map(task -> repository.values(task.getAttachmentSnapshotJson()))
                .flatMap(snapshot -> snapshot.values().stream()).anyMatch(array -> contains(array, documentId, versionId));
        if (!approved) {
            log.info("流程附件下载被拒绝，版本不在已批准快照中，instanceId={}，documentId={}，versionId={}", id,
                    documentId, versionId);
            throw new TodoNotFoundException();
        }
        documents.download(business(id), documentId, versionId, output);
    }

    private static boolean contains(JsonNode values, long doc, long version) {
        if (values == null || !values.isArray()) {
            return false;
        }
        for (var item : values) {
            if (documentId(item) == doc && versionId(item) == version) {
                return true;
            }
        }
        return false;
    }

    private static long documentId(JsonNode ref) {
        return Long.parseLong(ref.path("documentId").asString());
    }

    private static long versionId(JsonNode ref) {
        return Long.parseLong(ref.path("versionId").asString());
    }

    private static DocumentCenter.BusinessRecord business(long id) {
        return new DocumentCenter.BusinessRecord("workflow", "PROCESS_INSTANCE", Long.toString(id));
    }
}
