package me.lendev.flow.workbench.flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.lendev.flow.document.DocumentStorage;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_attachment;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "document.cleanup.enabled=false"
})
@Sql("/db/seed/workflow-examples.sql")
class WorkflowAttachmentApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private DocumentStorage storage;

    @Test
    void locksUploadedVersionAndKeepsRemovedAttachmentInHistory() throws Exception {
        var files = new ConcurrentHashMap<String, byte[]>();
        doAnswer(call -> {
            files.put(call.getArgument(0), call.getArgument(1, InputStream.class).readAllBytes());
            return null;
        }).when(storage).put(anyString(), any());
        doAnswer(call -> {
            try (var data = new ByteArrayInputStream(files.get(call.getArgument(0)))) {
                call.getArgument(1, DocumentStorage.Reader.class).read(data);
            }
            return null;
        }).when(storage).read(anyString(), any());

        String admin = loginAsAdmin();
        jdbc.update("INSERT INTO user_roles_role (userId,roleId) SELECT 1,id FROM role WHERE code LIKE 'WF_DEMO_%'");
        jdbc.update("INSERT INTO wf_role_handler (userId,roleId,kind,primaryRoleId) SELECT 1,id,'PRIMARY',id FROM role WHERE code LIKE 'WF_DEMO_%'");
        var definitions = data(getJson("/workflow/definitions", admin)).path("pageData");
        var template = definitions.valueStream().filter(row -> row.path("code").asString().equals("WF_DEMO_CONTRACT")).findFirst().orElseThrow();
        long definition = template.path("id").asLong();
        data(postJson("/workflow/definitions/" + definition + "/publish", admin, map("version", template.path("version").asLong())));
        var start = data(postJson("/workflow/instances", admin, map("requestKey", "attach-run", "definitionId", definition, "title", "Contract")));
        String flow = "/workflow/instances/" + start.path("instance").path("id").asLong();
        var task = active(start, "request");
        String uploadPath = flow + "/tasks/" + task.path("id").asLong() + "/attachments/wf_demo_files";
        var upload = data(mockMvc.perform(multipart(uploadPath).file(new MockMultipartFile("file", "合同.txt", "text/plain",
                "locked version".getBytes(StandardCharsets.UTF_8))).header(HttpHeaders.AUTHORIZATION, bearer(admin))));
        var refs = List.of(map("documentId", upload.path("documentId").asString(), "versionId", upload.path("versionId").asString()));
        String downloadPath = flow + "/attachments/" + upload.path("documentId").asString() + "/" + upload.path("versionId").asString();
        body(getJson(downloadPath, admin), 404);

        var review = act(admin, flow, start, "submit", "request", "attach-submit",
                map("fields", map("wf_demo_subject", "Contract", "wf_demo_files", refs)));
        assertEquals("合同.txt", review.path("attachmentNames").path(upload.path("versionId").asString()).asString());
        var returned = act(admin, flow, review, "reject", "legal", "attach-return", map("reason", "Revise"));
        var replacement = active(returned, "request");
        var other = data(mockMvc.perform(multipart(flow + "/tasks/" + replacement.path("id").asLong() + "/attachments/wf_demo_files")
                .file(new MockMultipartFile("file", "合同更新.txt", "text/plain", "new data".getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))));
        var changed = act(admin, flow, returned, "submit", "request", "attach-replace",
                map("fields", map("wf_demo_files", List.of(map("documentId", other.path("documentId").asString(),
                        "versionId", other.path("versionId").asString())))));
        assertEquals(other.path("versionId"), changed.path("values").path("wf_demo_files").get(0).path("versionId"));
        assertEquals("locked version", getJson(downloadPath, admin).andReturn().getResponse().getContentAsString());
        assertTrue(data(getJson(flow + "/history", admin)).path("pageData").valueStream().anyMatch(event ->
                event.path("attachmentNames").has(upload.path("versionId").asString())));
    }

    private JsonNode act(String token, String flow, JsonNode instance, String action, String node, String key,
                         Map<String, Object> details) throws Exception {
        var request = map("requestKey", key, "version", instance.path("instance").path("version").asLong(),
                "todoId", active(instance, node).path("todoId").asLong());
        request.putAll(details);
        return data(postJson(flow + "/actions/" + action, token, request));
    }

    private static JsonNode active(JsonNode detail, String node) {
        return detail.path("tasks").valueStream().filter(task -> task.path("canSubmit").asBoolean()
                && node.equals(task.path("nodeKey").asString())).findFirst().orElseThrow();
    }
}
