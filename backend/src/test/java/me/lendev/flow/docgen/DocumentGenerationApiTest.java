package me.lendev.flow.docgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import me.lendev.flow.docgen.render.DocxRenderer;
import me.lendev.flow.document.DocumentStorage;
import me.lendev.flow.document.DocumentCenter;
import me.lendev.flow.document.DocumentGenerationFiles;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:docgen_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "document.sftp.root=qa/docgen",
        "document.generation.samples.enabled=false",
        "document.generation.maintenance-enabled=false"
})
class DocumentGenerationApiTest extends ApiTestBase {

    private static final String ROOT = "/document-generation";
    private static final String SCHEMA = """
            {"type":"object","properties":{"title":{"type":"string"}},"required":["title"],"additionalProperties":false}
            """;

    @MockitoBean
    private DocumentStorage storage;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DocumentGenerationService service;
    @Autowired
    private DocumentGenerationFiles files;
    private final Map<String, byte[]> storedFiles = new ConcurrentHashMap<>();

    @BeforeEach
    void useMemoryStorage() throws Exception {
        storedFiles.clear();
        doAnswer(invocation -> {
            storedFiles.put(invocation.getArgument(0), invocation.<InputStream>getArgument(1).readAllBytes());
            return null;
        }).when(storage).put(anyString(), any());
        doAnswer(invocation -> {
            try (var stream = new ByteArrayInputStream(storedFiles.get(invocation.getArgument(0)))) {
                invocation.<DocumentStorage.Reader>getArgument(1).read(stream);
            }
            return null;
        }).when(storage).read(anyString(), any());
    }

    @Test
    void createsPublishesAndGeneratesUsingOneFixedCopy() throws Exception {
        String token = bearer(loginAsAdmin());
        var provision = data(mockMvc.perform(post(ROOT + "/admin/templates")
                .header(HttpHeaders.AUTHORIZATION, token).contentType("application/json")
                .content("{\"code\":\"api-fixture\",\"name\":\"测试模板\"}")));
        var template = provision.path("template").path("id").asString();
        var version = provision.path("version").path("id").asString();
        String versionUrl = ROOT + "/admin/templates/" + template + "/versions/" + version;
        var revision = data(mockMvc.perform(put(versionUrl)
                .header(HttpHeaders.AUTHORIZATION, token).header("If-Match", provision.path("version").path("etag").asString())
                .contentType("application/json").content("{\"inputSchema\":" + SCHEMA
                        + ",\"samples\":[{\"code\":\"one\",\"name\":\"单份\",\"input\":{\"title\":\"示例\"}}]}")));
        byte[] templateBytes = basicFixture();
        var uploaded = data(mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT, versionUrl + "/file")
                .file(new MockMultipartFile("file", "template.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", templateBytes))
                .header(HttpHeaders.AUTHORIZATION, token).header("If-Match", revision.path("etag").asString())));
        assertThat(storedFiles).hasSize(1);
        String storageKey = storedFiles.keySet().iterator().next();
        assertWordFormat(storedFiles.get(storageKey), "{{title}}");
        assertPreviewDoesNotCreateRecord(token, versionUrl, version, uploaded.path("etag").asString());
        var published = data(mockMvc.perform(post(versionUrl + "/publish").header(HttpHeaders.AUTHORIZATION, token)
                .header("If-Match", uploaded.path("etag").asString())));
        assertThat(published.path("status").asString()).isEqualTo("PUBLISHED");
        storedFiles.put(storageKey, templateBytes);
        var legacyTemplate = mockMvc.perform(get(versionUrl + "/file").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertWordFormat(legacyTemplate.getContentAsByteArray(), "{{title}}");
        assertThat(legacyTemplate.getContentLength()).isEqualTo(legacyTemplate.getContentAsByteArray().length);
        assertThat(storedFiles.get(storageKey)).isSameAs(templateBytes);
        mockMvc.perform(put(versionUrl).header(HttpHeaders.AUTHORIZATION, token)
                .header("If-Match", published.path("etag").asString()).contentType("application/json")
                .content("{\"inputSchema\":" + SCHEMA + "}")).andExpect(status().isConflict());

        String key = UUID.randomUUID().toString();
        String request = "{\"templateVersionId\":\"" + version + "\",\"input\":{\"title\":\"输出\"}}";
        JsonNode generated = run(token, key, request);
        String id = generated.path("id").asString();
        assertThat(generated.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run(token, key, request).path("id").asString()).isEqualTo(id);
        var history = data(mockMvc.perform(get(ROOT + "/generations")
                .param("templateId", template).param("status", "SUCCEEDED").header(HttpHeaders.AUTHORIZATION, token)));
        assertThat(history.path("total").asInt()).isEqualTo(1);
        assertThat(history.path("records").get(0).path("id").asString()).isEqualTo(id);
        String duplicateFields = request.replace("\"输出\"", "\"输出\",\"title\":\"重复\"");
        mockMvc.perform(post(ROOT + "/generations").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(duplicateFields)).andExpect(status().isBadRequest());
        mockMvc.perform(post(ROOT + "/generations").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(" ".repeat(4 * 1024 * 1024) + request)).andExpect(status().isContentTooLarge());
        mockMvc.perform(post(ROOT + "/generations").header(HttpHeaders.AUTHORIZATION, token)
                        .header("Idempotency-Key", key).contentType("application/json").content(request.replace("输出", "变更")))
                .andExpect(status().isConflict());
        var download = mockMvc.perform(get(ROOT + "/generations/" + id + "/file")
                .header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ContentDisposition.parse(download.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .startsWith("测试模板-" + id).endsWith(".docx");
        assertWordFormat(download.getContentAsByteArray(), "输出");
        assertThat(data(mockMvc.perform(get(ROOT + "/generations/" + id + "/input")
                .header(HttpHeaders.AUTHORIZATION, token))).path("title").asString()).isEqualTo("输出");

        assertInputOwnershipAndRetention(token, id);
        assertReclaimsUnusedTemplateFile(token, versionUrl, version, templateBytes);

        var failedKey = UUID.randomUUID().toString();
        doThrow(new IOException("测试存储不可用")).when(storage).read(anyString(), any());
        var failed = run(token, failedKey, request);
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").asString()).isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(run(token, failedKey, request).path("id").asString()).isEqualTo(failed.path("id").asString());
    }

    private void assertPreviewDoesNotCreateRecord(String token, String versionUrl, String version, String etag)
            throws Exception {
        var trial = mockMvc.perform(post(versionUrl + "/test").header(HttpHeaders.AUTHORIZATION, token)
                        .header("If-Match", etag).contentType("application/json")
                        .content("{\"input\":{\"title\":\"试生成\"}}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(trial.getContentType()).isEqualTo(DocGenerationSupport.DOCX_TYPE);
        assertWordFormat(trial.getContentAsByteArray(), "试生成");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM biz_doc_generation WHERE templateVersionId = ?")
                .param(Long.parseLong(version)).query(Integer.class).single()).isZero();
    }

    private void assertInputOwnershipAndRetention(String token, String id) throws Exception {
        var otherOwner = DocGenerationModels.Actor.app(1234);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generation(otherOwner, Long.parseLong(id)))
                .isInstanceOfSatisfying(DocGenerationFailure.class, failure -> {
                    assertThat(failure.status().value()).isEqualTo(404);
                });
        jdbc.sql("UPDATE biz_doc_generation SET finishedAt = ? WHERE id = ?")
                .params(LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(31), Long.parseLong(id)).update();
        mockMvc.perform(get(ROOT + "/generations/" + id + "/input").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isGone());
        service.maintain();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM biz_doc_generation WHERE id = ? AND inputJson IS NULL")
                .param(Long.parseLong(id)).query(Integer.class).single()).isEqualTo(1);
        mockMvc.perform(get(ROOT + "/generations/" + id + "/file").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    private void assertReclaimsUnusedTemplateFile(String token, String versionUrl, String version, byte[] templateBytes)
            throws Exception {
        var unused = DocGenerationSupport.system("未锁定文件测试", () -> files.upload(
                DocumentGenerationFiles.Target.TEMPLATE_VERSION, Long.parseLong(version),
                null, 0, "unused", new DocumentCenter.Upload("unused.docx", DocGenerationSupport.DOCX_TYPE,
                        templateBytes.length, new ByteArrayInputStream(templateBytes))));
        jdbc.sql("UPDATE doc_document_version SET updatedAt = ? WHERE id = ?")
                .params(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(10), unused.id()).update();
        service.maintain();
        assertThat(jdbc.sql("SELECT status FROM doc_document_version WHERE id = ?").param(unused.id())
                .query(String.class).single()).isEqualTo("DELETE_PENDING");
        mockMvc.perform(get(versionUrl + "/file").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    @Test
    void copiesAndReleasesANewVersionWhilePreservingExistingOutputs() throws Exception {
        long version = seedFixture("template-copy");
        String token = bearer(loginAsAdmin());
        long template = templateFor(version);
        String admin = ROOT + "/admin/templates/" + template;
        String versions = admin + "/versions";
        var listing = data(mockMvc.perform(get(ROOT + "/admin/templates").header(HttpHeaders.AUTHORIZATION, token)
                .param("keyword", "template-copy").param("published", "true").param("enabled", "true")));
        assertThat(listing.path("total").asInt()).isEqualTo(1);
        var old = data(mockMvc.perform(get(versions + "/" + version).header(HttpHeaders.AUTHORIZATION, token)));
        String key = UUID.randomUUID().toString();
        String request = "{\"templateVersionId\":\"" + version + "\",\"input\":{\"title\":\"旧版\"}}";
        String id = run(token, key, request).path("id").asString();
        var copy = data(mockMvc.perform(post(versions).header(HttpHeaders.AUTHORIZATION, token).contentType("application/json")
                .content("{\"sourceVersionId\":\"" + version + "\"}")));
        assertThat(copy.path("inputSchema")).isEqualTo(old.path("inputSchema"));
        assertThat(copy.path("samples")).isEqualTo(old.path("samples"));
        assertThat(copy.path("documentVersionId")).isNotEqualTo(old.path("documentVersionId"));
        String draft = versions + "/" + copy.path("id").asString();
        mockMvc.perform(post(draft + "/publish").header(HttpHeaders.AUTHORIZATION, token)
                        .header("If-Match", old.path("etag").asString())).andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.ETAG, copy.path("etag").asString()));
        var released = data(mockMvc.perform(post(draft + "/publish").header(HttpHeaders.AUTHORIZATION, token)
                .header("If-Match", copy.path("etag").asString())));
        assertThat(released.path("versionNo").asInt()).isEqualTo(2);
        var allVersions = data(mockMvc.perform(get(versions).header(HttpHeaders.AUTHORIZATION, token)));
        assertThat(allVersions.valueStream().map(item -> item.path("versionNo").asInt())).containsExactly(2, 1);
        var current = data(mockMvc.perform(get(ROOT + "/templates/" + template + "/current")
                .header(HttpHeaders.AUTHORIZATION, token)));
        assertThat(current.path("versionId")).isEqualTo(copy.path("id"));
        mockMvc.perform(post(ROOT + "/generations").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(request)).andExpect(status().isConflict());

        var disabled = data(mockMvc.perform(patch(admin).header(HttpHeaders.AUTHORIZATION, token)
                .contentType("application/json").content("{\"name\":\"改名模板\",\"description\":\"已停用\",\"enabled\":false}")));
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        mockMvc.perform(get(ROOT + "/templates/" + template + "/current").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        assertThat(run(token, key, request).path("id").asString()).isEqualTo(id);
        mockMvc.perform(get(ROOT + "/generations/" + id + "/file").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        var today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        var dated = data(mockMvc.perform(get(ROOT + "/generations").header(HttpHeaders.AUTHORIZATION, token)
                .param("templateId", Long.toString(template)).param("startDate", today).param("endDate", today)));
        assertThat(dated.path("total").asInt()).isEqualTo(1);
    }

    @Test
    void recoversExactlyOneUploadedOutputAndReclaimsConflictingFiles() throws Exception {
        long version = seedFixture("recover-fixture");
        long noUpload = staleRecord(version);
        long single = staleRecord(version);
        long duplicate = staleRecord(version);
        var expected = detachedOutput(single);
        var first = detachedOutput(duplicate);
        var second = detachedOutput(duplicate);

        service.maintain();

        var actor = DocGenerationModels.Actor.app(42);
        assertThat(service.generation(actor, noUpload).errorCode()).isEqualTo("GENERATION_TIMEOUT");
        assertThat(service.generation(actor, single).status()).isEqualTo("SUCCEEDED");
        assertThat(service.generation(actor, duplicate).errorCode()).isEqualTo("GENERATION_OUTPUT_CONFLICT");
        assertThat(jdbc.sql("SELECT outputVersionId FROM biz_doc_generation WHERE id = ?")
                .param(single).query(Long.class).single()).isEqualTo(expected.id());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM doc_document_version WHERE id IN (?, ?) AND status = 'DELETE_PENDING'")
                .params(first.id(), second.id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void listsFixedTemplateFilesAndOnlyTheCurrentEmployeesOutputs() throws Exception {
        long version = seedFixture("catalog_files");
        long template = templateFor(version);
        String admin = loginAsAdmin();
        String endpoint = ROOT + "/files";
        String versions = ROOT + "/admin/templates/" + template + "/versions";
        var draft = data(postJson(versions, admin, map("sourceVersionId", Long.toString(version))));
        data(postJson(versions, admin, map()));
        var templates = data(getJson(endpoint, admin, "type", "TEMPLATE_VERSION", "keyword", "catalog_", "pageSize", 1));
        assertThat(templates.path("total").asInt()).isEqualTo(2);
        var first = templates.path("records").get(0);
        assertThat(first.path("templateVersionId")).isEqualTo(draft.path("id"));
        assertThat(first.path("status").asString()).isEqualTo("DRAFT");
        assertThat(first.path("type").asString()).isEqualTo("TEMPLATE_VERSION");
        assertThat(first.path("generationId").isNull()).isTrue();
        assertThat(first.path("originalFileName").asString()).isEqualTo("template.docx");
        assertThat(first.path("sizeBytes").asLong()).isPositive();
        String draftPath = jdbc.sql("SELECT storageKey FROM doc_document_version WHERE id = ?")
                .param(Long.parseLong(draft.path("documentVersionId").asString())).query(String.class).single();
        assertThat(first.path("sftpPath").asString()).isEqualTo("qa/docgen/" + draftPath);
        var historical = data(getJson(endpoint, admin, "type", "TEMPLATE_VERSION", "keyword", "catalog_files",
                "pageSize", 1, "pageNo", 2)).path("records").get(0);
        assertThat(historical.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(historical.path("templateVersionId").asString()).isEqualTo(Long.toString(version));
        assertThat(data(getJson(endpoint, admin, "type", "TEMPLATE_VERSION", "keyword", "%"))
                .path("total").asInt()).isZero();

        String request = "{\"templateVersionId\":\"" + version + "\",\"input\":{\"title\":\"本人成品\"}}";
        var generated = run(bearer(admin), UUID.randomUUID().toString(), request);
        service.generate(DocGenerationModels.Actor.app(42), UUID.randomUUID().toString(), version,
                objectMapper.readTree("{\"title\":\"应用成品\"}"));
        var results = data(getJson(endpoint, admin, "type", "GENERATION", "keyword", "catalog_files"));
        assertThat(results.path("total").asInt()).isEqualTo(1);
        var output = results.path("records").get(0);
        assertThat(output.path("generationId")).isEqualTo(generated.path("id"));
        assertThat(output.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(output.path("type").asString()).isEqualTo("GENERATION");
        assertThat(output.path("originalFileName").asString()).endsWith(".docx");
        String outputPath = jdbc.sql("""
                SELECT f.storageKey FROM biz_doc_generation g JOIN doc_document_version f ON f.id = g.outputVersionId
                WHERE g.id = ?
                """).param(Long.parseLong(generated.path("id").asString())).query(String.class).single();
        assertThat(output.path("sftpPath").asString()).isEqualTo("qa/docgen/" + outputPath);
        assertThat(output.has("storageKey")).isFalse();
        assertThat(output.has("inputJson")).isFalse();

        String viewer = fileCatalogViewer("file_catalog_viewer", "DocumentGenerationView");
        assertThat(data(getJson(endpoint, viewer, "type", "GENERATION")).path("total").asInt()).isZero();
        code(getJson(endpoint, viewer, "type", "TEMPLATE_VERSION"), 11003);
        String docOnly = fileCatalogViewer("file_catalog_doc_only", "DocumentCenterView");
        code(getJson(endpoint, docOnly, "type", "GENERATION"), 11003);
        String templateManager = fileCatalogViewer("file_catalog_manager", "DocumentGenerationTemplateManage");
        assertThat(data(getJson(endpoint, templateManager, "type", "TEMPLATE_VERSION", "keyword", "catalog_files"))
                .path("total").asInt()).isEqualTo(2);
        code(getJson(endpoint, templateManager, "type", "GENERATION"), 11003);
        body(getJson(endpoint, admin, "type", "GENERATION", "pageNo", 0), 400);
    }

    private String fileCatalogViewer(String name, String permission) throws Exception {
        jdbc.sql("INSERT INTO role(code,name,enable) VALUES(?,?,1)").params(name, name).update();
        long role = jdbc.sql("SELECT id FROM role WHERE code = ?").param(name).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO `user`(username,password,identityKey,enable)
                SELECT ?,password,RANDOM_UUID(),1 FROM `user` WHERE username = 'admin'
                """).param(name).update();
        jdbc.sql("INSERT INTO user_roles_role(userId,roleId) SELECT id,? FROM `user` WHERE username = ?")
                .params(role, name).update();
        jdbc.sql("INSERT INTO role_permissions_permission(roleId,permissionId) SELECT ?,id FROM permission WHERE code IN (?,?)")
                .params(role, "DocumentCenterView", permission).update();
        return login(name, ADMIN_PASSWORD);
    }

    @Test
    void rejectsInvalidDraftsAndKeepsTheTemplateUnpublished() throws Exception {
        String token = bearer(loginAsAdmin());
        String admin = ROOT + "/admin/templates";
        String create = "{\"code\":\"admin-validation\",\"name\":\"校验模板\"}";
        var provision = data(mockMvc.perform(post(admin).header(HttpHeaders.AUTHORIZATION, token)
                .contentType("application/json").content(create)));
        mockMvc.perform(post(admin).header(HttpHeaders.AUTHORIZATION, token).contentType("application/json")
                .content(create)).andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> seedFixture("admin-validation"))
                .isInstanceOfSatisfying(DocGenerationFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("TEMPLATE_CODE_CONFLICT");
                });
        String templateId = provision.path("template").path("id").asString();
        String versionId = provision.path("version").path("id").asString();
        String draft = admin + "/" + templateId + "/versions/" + versionId;
        String etag = provision.path("version").path("etag").asString();
        mockMvc.perform(post(draft + "/publish").header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, etag)).andExpect(status().isUnprocessableContent());
        var invalidConfigurations = java.util.List.of("{\"inputSchema\":[]}", "{\"inputSchema\":" + SCHEMA + ",\"samples\":{}}",
                "{\"inputSchema\":" + SCHEMA + ",\"samples\":[{\"code\":\"a\",\"name\":\"测试\",\"input\":[]}]}");
        for (String invalid : invalidConfigurations) {
            mockMvc.perform(put(draft).header(HttpHeaders.AUTHORIZATION, token).header(HttpHeaders.IF_MATCH, etag)
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(put(draft).header(HttpHeaders.AUTHORIZATION, token).header(HttpHeaders.IF_MATCH, etag)
                        .contentType("application/json").content("{\"inputSchema\":{\"description\":\"" + "x".repeat(128 * 1024) + "\"}}"))
                .andExpect(status().isContentTooLarge());
        mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT, draft + "/file")
                        .file(new MockMultipartFile("file", "file.txt", "text/plain", new byte[]{1, 2, 3}))
                        .header(HttpHeaders.AUTHORIZATION, token).header(HttpHeaders.IF_MATCH, etag))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT, draft + "/file")
                        .file(new MockMultipartFile("file", "file.docx", DocGenerationSupport.DOCX_TYPE, new byte[]{1, 2, 3}))
                        .header(HttpHeaders.AUTHORIZATION, token).header(HttpHeaders.IF_MATCH, etag))
                .andExpect(status().isUnsupportedMediaType());
        byte[] oversized = new byte[DocxRenderer.MAX_DOCX_BYTES + 2];
        var stream = new ByteArrayInputStream(oversized);
        var oversizedUpload = new MockMultipartFile("file", "file.docx", DocGenerationSupport.DOCX_TYPE, oversized) {
            @Override
            public InputStream getInputStream() {
                return stream;
            }
        };
        mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT, draft + "/file").file(oversizedUpload)
                        .header(HttpHeaders.AUTHORIZATION, token).header(HttpHeaders.IF_MATCH, etag))
                .andExpect(status().isContentTooLarge());
        assertThat(stream.available()).isOne();
        var updated = data(mockMvc.perform(put(draft).header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, etag).contentType("application/json")
                .content("{\"inputSchema\":" + SCHEMA + ",\"exampleJson\":{\"title\":false}}")));
        var upload = data(mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT, draft + "/file")
                .file(new MockMultipartFile("file", "file.docx", DocGenerationSupport.DOCX_TYPE, basicFixture()))
                .header(HttpHeaders.AUTHORIZATION, token).header(HttpHeaders.IF_MATCH, updated.path("etag").asString())));
        mockMvc.perform(post(draft + "/publish").header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, upload.path("etag").asString())).andExpect(status().isUnprocessableContent());
        mockMvc.perform(get(ROOT + "/templates/" + templateId + "/current").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
    }

    private long seedFixture(String code) throws Exception {
        return service.seedTemplate(new DocGenerationModels.SeedTemplate(code, "测试模板", null, basicFixture(),
                objectMapper.readTree(SCHEMA), objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"code\":\"basic\",\"name\":\"基础\",\"input\":{\"title\":\"示例\"}}]")));
    }

    private long templateFor(long version) {
        return jdbc.sql("SELECT templateId FROM biz_doc_template_version WHERE id = ?")
                .param(version).query(Long.class).single();
    }

    private long staleRecord(long version) {
        String key = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO biz_doc_generation (templateVersionId, createdByAppId, requestKey, requestHash, status, createdAt)
                VALUES (?, 42, ?, ?, 'RUNNING', ?)
                """).params(version, key, "0".repeat(64), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10)).update();
        return jdbc.sql("SELECT id FROM biz_doc_generation WHERE createdByAppId = 42 AND requestKey = ?")
                .param(key).query(Long.class).single();
    }

    private DocumentCenter.Version detachedOutput(long id) throws Exception {
        byte[] bytes = basicFixture();
        return DocGenerationSupport.system("写入待补偿成品", () -> files.upload(DocumentGenerationFiles.Target.GENERATION,
                id, null, 0, "ready", new DocumentCenter.Upload("ready.docx", DocGenerationSupport.DOCX_TYPE,
                        bytes.length, new ByteArrayInputStream(bytes))));
    }

    private JsonNode run(String token, String key, String request) throws Exception {
        return data(mockMvc.perform(post(ROOT + "/generations").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key).contentType("application/json").content(request)));
    }

    private byte[] basicFixture() throws Exception {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var out = new java.io.ByteArrayOutputStream()) {
            var run = doc.createParagraph().createRun();
            run.setFontFamily("Arial");
            run.setText("{{title}}");
            doc.enforceUpdateFields();
            var page = doc.createParagraph().getCTP().addNewFldSimple();
            page.setInstr("PAGE");
            page.setDirty(true);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private void assertWordFormat(byte[] file, String content) throws Exception {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(file))) {
            assertThat(doc.getParagraphs()).anyMatch(p -> p.getText().equals(content));
            assertThat(doc.getParagraphs().getFirst().getRuns().getFirst().getFontFamily()).isEqualTo("SimSun");
            assertThat(doc.getSettings().getCTSettings().xmlText())
                    .contains("compatibilityMode", ":val=\"15\"");
            assertThat(doc.getSettings().getCTSettings().isSetUpdateFields()).isFalse();
            assertThat(doc.getDocument().xmlText()).contains("PAGE").doesNotContain(":dirty=");
        }
    }
}
