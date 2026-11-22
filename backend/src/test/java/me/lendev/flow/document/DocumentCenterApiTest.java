package me.lendev.flow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import cn.dev33.satoken.stp.StpUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.document.DocumentCenter.BusinessRecord;
import me.lendev.flow.document.DocumentCenter.Filter;
import me.lendev.flow.document.DocumentCenter.Metadata;
import me.lendev.flow.document.DocumentCenter.Upload;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:document_center;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "document.sftp.root=qa/documents",
        "document.cleanup.enabled=false"
})
class DocumentCenterApiTest extends ApiTestBase {

    @MockitoBean
    private DocumentStorage storage;
    @Autowired
    private DocumentCenter documents;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private DocumentRepository repository;
    @Autowired
    private DocumentMutations mutations;
    @Autowired
    private DocumentTransfers transfers;

    @BeforeEach
    void memoryStorage() throws Exception {
        Map<String, byte[]> content = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            var source = invocation.getArgument(1, java.io.InputStream.class);
            content.put(invocation.getArgument(0), source.readAllBytes());
            return null;
        }).when(storage).put(anyString(), any());
        doAnswer(invocation -> {
            byte[] data = content.get(invocation.getArgument(0));
            try (var input = new ByteArrayInputStream(data)) {
                invocation.getArgument(1, DocumentStorage.Reader.class).read(input);
            }
            return null;
        }).when(storage).read(anyString(), any());
    }

    @Test
    void independentDocumentCanBeListedEditedDownloadedAndDeletedWithAudit() throws Exception {
        var token = loginAsAdmin();
        byte[] payload = "原始内容".getBytes(StandardCharsets.UTF_8);
        long product = configureDimensions();
        var metadata = new MockMultipartFile("metadata", "", "application/json",
                writeJson(Map.of("name", "合同_初稿", "productId", product,
                        "dimensions", Map.of(1, "归档_批次", 2, Long.toString(product)))).getBytes(StandardCharsets.UTF_8));
        var file = new MockMultipartFile("file", "合同.txt", "text/plain", payload);
        var uploaded = data(mockMvc.perform(multipart("/documents").file(metadata).file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))));
        long documentId = uploaded.path("id").asLong();
        String versionNo = uploaded.path("latestVersion").path("versionNo").asString();
        assertThat(versionNo).matches("[0-9]{8}");
        var storedVersion = repository.version(documentId, uploaded.path("latestVersion").path("id").asLong());
        String uploadMonth = storedVersion.getCreatedAt().atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("uuuuMM"));
        assertThat(storedVersion.getStorageKey()).matches(uploadMonth + "/1/[0-9a-f-]{36}");
        verify(storage).put(org.mockito.ArgumentMatchers.eq(storedVersion.getStorageKey()), any());
        var listed = data(getJson("/documents", token, "keyword", "合同_", "pageNo", 1, "pageSize", 10))
                .path("records").get(0);
        assertThat(listed.path("id").asLong()).isEqualTo(documentId);
        assertThat(listed.path("sftpPath").asString()).isEqualTo("qa/documents/" + storedVersion.getStorageKey());
        assertThat(uploaded.has("sftpPath")).isFalse();
        assertThat(data(getJson("/documents", token, "keyword", "合同%")).path("total").asLong()).isZero();
        assertThat(data(getJson("/documents", token, "dimension1Value", "归档_", "dimension2Value", product,
                "productId", product)).path("total").asLong()).isEqualTo(1);
        assertThat(data(getJson("/documents", token, "dimension1Value", "归档%", "dimension2Value", product))
                .path("total").asLong()).isZero();
        assertThat(data(getJson("/documents", token, "dimension1Value", "归档_", "dimension2Value", product + 1))
                .path("total").asLong()).isZero();

        var changed = data(mockMvc.perform(put("/documents/{id}", documentId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType("application/json")
                .content(writeJson(Map.of("name", "合同_定稿")))));
        assertThat(changed.path("name").asString()).isEqualTo("合同_定稿");
        assertThat(changed.path("dimensions").size()).isZero();
        assertThat(changed.path("productId").isNull()).isTrue();
        var downloaded = mockMvc.perform(get("/documents/{id}/download", documentId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn().getResponse();
        assertThat(downloaded.getStatus()).isEqualTo(200);
        assertThat(downloaded.getContentAsByteArray()).isEqualTo(payload);
        assertThat(ContentDisposition.parse(downloaded.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("合同_" + versionNo + ".txt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE "
                + "operationCode = 'business.document.download' AND status = 'PENDING'", Integer.class)).isPositive();

        body(deleteJson("/documents/" + documentId, token));
        body(getJson("/documents/" + documentId, token), 404);
        String deleteOperation = jdbc.queryForObject(
                "SELECT cleanupAuditOperationId FROM doc_document_version WHERE documentId = ?", String.class, documentId);
        assertThat(deleteOperation).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE operationId = ? "
                + "AND operationCode = 'business.document.delete'", Integer.class, deleteOperation)).isEqualTo(1);
    }

    @Test
    void businessBindingRollsBackAndDownloadsOnlyThePinnedVersion() {
        SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        StpUtil.login(1);
        var first = documents.upload(null, null, new Metadata("验收记录", null, null, Map.of()), upload("第一版"));
        var business = new BusinessRecord("test", "REVIEW", "A");
        var other = new BusinessRecord("test", "REVIEW", "B");
        var transaction = new TransactionTemplate(transactions);

        transaction.executeWithoutResult(status -> {
            documents.bind(business, first.documentId(), first.id());
            status.setRollbackOnly();
        });
        assertThat(documents.detail(null, first.documentId()).businessVersionId()).isNull();
        documents.bind(business, first.documentId(), first.id());
        var next = documents.upload(business, first.documentId(), null, upload("第二版"));
        if (first.versionNo().equals(next.versionNo().substring(0, 8))) {
            assertThat(next.versionNo()).isEqualTo(first.versionNo() + "V2");
        }

        assertThat(documents.detail(business, first.documentId()).latestVersion().id()).isEqualTo(next.id());
        assertThat(documents.detail(business, first.documentId()).businessVersionId()).isEqualTo(first.id());
        assertThatThrownBy(() -> documents.bind(other, first.documentId(), first.id()))
                .isInstanceOfSatisfying(DocumentFailure.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> documents.download(business, first.documentId(), next.id(), (version, content) -> {
        }))
                .isInstanceOfSatisfying(DocumentFailure.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        documents.download(business, first.documentId(), null, (version, content) -> {
            assertThat(version.id()).isEqualTo(first.id());
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("第一版");
        });
        documents.detach(business, first.documentId());
        assertThat(documents.detail(null, first.documentId()).businessVersionId()).isNull();
    }

    @Test
    void detailReadsCommittedSnapshotWhileTheDocumentRowIsBeingUpdated() throws Exception {
        var token = loginAsAdmin();
        long id = create(token, "Committed name").path("id").asLong();
        var updated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE doc_document SET name = 'Uncommitted name' WHERE id = ?", id);
                updated.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("文档详情读取超时");
                    }
                    status.setRollbackOnly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
                var reader = executor.submit(() -> data(getJson("/documents/" + id, token)));
                var result = reader.get(5, TimeUnit.SECONDS);
                assertThat(result.path("name").asString()).isEqualTo("Committed name");
                assertThat(result.path("versions").size()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void publishingRechecksBusinessOwnershipAfterTheExternalTransfer() throws Exception {
        SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        StpUtil.login(1);
        var first = documents.upload(null, null, new Metadata("待转移文档", null, null, Map.of()), upload("已归档内容"));
        var original = new BusinessRecord("test", "REVIEW", "SOURCE");
        var destination = new BusinessRecord("test", "REVIEW", "DESTINATION");
        documents.bind(original, first.documentId(), first.id());

        doAnswer(invocation -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var change = executor.submit(() -> {
                    SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
                    try (var identity = AuditSystemContext.open("模拟业务记录转移")) {
                        StpUtil.login(1);
                        documents.detach(original, first.documentId());
                        documents.bind(destination, first.documentId(), first.id());
                    } finally {
                        SaTokenContextJakartaServletUtil.clearContext();
                    }
                });
                try {
                    change.get(10, TimeUnit.SECONDS);
                } finally {
                    change.cancel(true);
                }
            }
            invocation.getArgument(1, java.io.InputStream.class).readAllBytes();
            return null;
        }).when(storage).put(anyString(), any());

        var incoming = upload("仅原业务可见的新内容");
        assertThatThrownBy(() -> documents.upload(original, first.documentId(), null, incoming))
                .isInstanceOfSatisfying(DocumentFailure.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(documents.detail(destination, first.documentId()).versions()).containsExactly(first);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM doc_document_version WHERE documentId = ? AND status = 'FAILED'")
                .param(first.documentId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedTransferLeavesTheDocumentOutOfSearchAndQueuesRecovery(CapturedOutput output) throws Exception {
        var token = loginAsAdmin();
        doThrow(new IOException("test storage unavailable")).when(storage).put(anyString(), any());
        var metadata = new MockMultipartFile("metadata", "", "application/json",
                writeJson(Map.of("name", "上传失败的文档")).getBytes(StandardCharsets.UTF_8));
        var file = new MockMultipartFile("file", "fail.txt", "text/plain", new byte[]{1});
        var result = mockMvc.perform(multipart("/documents").file(metadata).file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
        var response = body(result, 503);
        var requestId = result.andReturn().getResponse().getHeader("X-Request-ID");
        assertThat(requestId).isNotBlank();
        assertThat(response.path("code").asInt()).isEqualTo(503);
        assertThat(response.path("message").asString()).isEqualTo("暂时无法保存文件，请稍后重新上传");
        assertThat(output.getAll())
                .contains("文档存储上传失败，requestId=" + requestId, "documentId=", "versionId=", "errorType=IOException", "请检查 SFTP")
                .doesNotContain("test storage unavailable");
        assertThat(data(getJson("/documents", token, "keyword", "上传失败的文档")).path("total").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_document_version WHERE originalFileName = 'fail.txt' "
                + "AND status = 'FAILED' AND dailySequence IS NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingUploadFilesGetAPromptToSelectAFile() throws Exception {
        var token = loginAsAdmin();
        var initial = mockMvc.perform(multipart("/documents").header(HttpHeaders.AUTHORIZATION, bearer(token)));
        assertThat(body(initial, 400).path("message").asString()).isEqualTo("请选择要上传的文件");
        var version = mockMvc.perform(multipart("/documents/1/versions")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"));
        assertThat(body(version, 400).path("message").asString()).isEqualTo("Select a file to upload");
    }

    @Test
    void cleanupRetriesExpiredLeasesAndCannotCompleteAnOlderLease() throws Exception {
        var token = loginAsAdmin();
        var document = create(token, "Cleanup lease");
        long id = document.path("id").asLong();
        long version = document.path("latestVersion").path("id").asLong();
        String key = repository.version(id, version).getStorageKey();
        body(deleteJson("/documents/" + id, token));
        doThrow(new IOException("remote temporarily unavailable")).doNothing().when(storage).delete(key);
        var worker = new DocumentCleanup(repository, mutations, transfers,
                Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC));
        var expired = LocalDateTime.of(2000, 1, 1, 0, 0);
        assertThat(mutations.claimCleanup(id, version, expired, expired)).isNull();
        assertThat(mutations.claimCleanup(id, -1, expired, expired)).isNull();

        worker.cleanup();
        assertThat(repository.version(id, version).getStatus()).isEqualTo("DELETE_PENDING");
        mutations.cleaned(id, version, expired);
        assertThat(repository.version(id, version).getStatus()).isEqualTo("DELETE_PENDING");
        worker.cleanup();
        assertThat(repository.version(id, version).getStatus()).isEqualTo("DELETED");
        verify(storage, times(2)).delete(key);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM sys_audit_outbox
                        WHERE operationCode = 'business.document.cleanup' AND result = 'SUCCESS' AND operationId = ?
                        """).param(repository.version(id, version).getCleanupAuditOperationId())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void optionsAndVersionsAreAvailableThroughTheHttpApi() throws Exception {
        var token = loginAsAdmin();
        assertThat(data(getJson("/documents/options", token)).path("dimensions").size()).isEqualTo(6);
        var document = create(token, "Version history");
        String url = "/documents/" + document.path("id").asLong();
        var first = document.path("latestVersion");
        var file = new MockMultipartFile("file", "new.txt", "text/plain", new byte[]{2});
        var second = data(mockMvc.perform(multipart(url + "/versions").file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))));
        assertThat(second.path("id").asLong()).isNotEqualTo(first.path("id").asLong());
        long documentId = document.path("id").asLong();
        var versions = data(getJson(url, token)).path("versions");
        assertThat(versions.size()).isEqualTo(2);
        var expectedVersions = List.of(second, first);
        for (int i = 0; i < expectedVersions.size(); i++) {
            long versionId = expectedVersions.get(i).path("id").asLong();
            assertThat(versions.get(i).path("id").asLong()).isEqualTo(versionId);
            assertThat(versions.get(i).path("sftpPath").asString())
                    .isEqualTo("qa/documents/" + repository.version(documentId, versionId).getStorageKey());
        }
        body(getJson(url + "/download", token, "versionId", -1), 404);
        doThrow(new IOException("storage failed")).when(storage).read(anyString(), any());
        var failed = getJson(url + "/download", token);
        assertThat(body(failed, 503).path("code").asInt()).isEqualTo(503);
        assertThat(failed.andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void businessUploadsEnforceSizeAndNormalizeUnusableMediaTypes() throws Exception {
        SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        StpUtil.login(1);
        var metadata = new Metadata("Upload validation", null, null, Map.of());
        assertThatThrownBy(() -> documents.upload(null, null, metadata, null))
                .isInstanceOf(BadRequestException.class);
        var tooLarge = new Upload("huge.txt", "text/plain", 5 * 1024 * 1024 + 1, InputStream.nullInputStream());
        assertThatThrownBy(() -> documents.upload(null, null, metadata, tooLarge))
                .isInstanceOfSatisfying(DocumentFailure.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        var empty = new Upload("empty.txt", null, 0, InputStream.nullInputStream());
        assertThat(documents.upload(null, null, metadata, empty).contentType()).isEqualTo("application/octet-stream");
        var invalidType = new Upload("invalid-type.txt", "invalid media type", 0, InputStream.nullInputStream());
        assertThat(documents.upload(null, null, metadata, invalidType).contentType()).isEqualTo("application/octet-stream");

        var tooShort = new Upload("truncated.txt", "text/plain", 1, InputStream.nullInputStream());
        assertThatThrownBy(() -> documents.upload(null, null, metadata, tooShort))
                .isInstanceOf(BadRequestException.class);
        doAnswer(invocation -> {
            try (var input = invocation.getArgument(1, InputStream.class)) {
                while (input.read() != -1) {
                    // 模拟逐字节读取的存储实现。
                }
            }
            return null;
        }).when(storage).put(anyString(), any());
        var tooLong = new Upload("oversized.txt", "text/plain", 1, new ByteArrayInputStream(new byte[]{1, 2}));
        assertThatThrownBy(() -> documents.upload(null, null, metadata, tooLong))
                .isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM doc_document_version
                WHERE originalFileName IN ('truncated.txt', 'oversized.txt') AND status = 'FAILED'
                """, Integer.class)).isEqualTo(2);

        var transfer = upload("blocked in transaction");
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                documents.upload(null, null, metadata, transfer)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
    }

    @Test
    void metadataValidationPreservesHistoricValuesAndRejectsInvalidFilters() throws Exception {
        String token = loginAsAdmin();
        long department = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM sys_department", Long.class);
        jdbc.update("INSERT INTO sys_department(id, name) VALUES (?, 'Archive')", department);
        SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        StpUtil.login(1);
        var metadata = new Metadata("Archive copy", department, null, Map.of());
        var first = documents.upload(null, null, metadata, upload("archive"));
        assertThat(documents.detail(null, first.documentId()).departmentId()).isEqualTo(department);
        jdbc.update("UPDATE sys_department SET enabled = FALSE WHERE id = ?", department);
        assertThat(documents.update(null, first.documentId(), metadata).departmentId()).isEqualTo(department);
        var another = upload("another archive");
        assertThatThrownBy(() -> documents.upload(null, null, metadata, another))
                .isInstanceOf(BadRequestException.class).hasMessage("error.document.department");

        var owner = new BusinessRecord("fixture", "ARCHIVE", "001");
        long documentId = first.documentId();
        assertThatThrownBy(() -> documents.bind(owner, documentId, -1))
                .isInstanceOf(DocumentFailure.class).hasMessage("error.document.business-version");
        var bound = documents.bind(owner, documentId, first.id());
        assertThat(documents.bind(owner, documentId, first.id())).isEqualTo(bound);
        body(getJson("/documents", token, "pageNo", 0), 400);
        body(getJson("/documents", token, "productId", -1), 400);
        SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        StpUtil.login(1);
        var invalidSlot = new Filter(null, null, null, null, null, Map.of(7, "unknown"));
        assertThatThrownBy(() -> documents.list(null, invalidSlot)).hasMessage("error.document.request");
    }

    @Test
    void dimensionsKeepDisabledValuesButRejectNewReferencesAndMalformedSelections() throws Exception {
        SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        StpUtil.login(1);
        long option = jdbc.queryForObject("""
                SELECT id FROM sys_param_item WHERE code = 'DIMENSION_1'
                AND groupId = (SELECT id FROM sys_param_group WHERE code = 'WORKBENCH_TODO_DIMENSION')
                """, Long.class);
        jdbc.update("UPDATE sys_param_item SET enable = 1 WHERE id = ?", option);
        jdbc.update("""
                UPDATE sys_param_item SET enable = 1, value1 = 'SELECT', value2 = 'WORKBENCH_TODO_DIMENSION'
                WHERE code = 'DIMENSION_5' AND groupId = (SELECT id FROM sys_param_group WHERE code = 'DOC_DIMENSION')
                """);
        var valid = new Metadata("Shared option", null, null, Map.of(5, "0" + option));
        var version = documents.upload(null, null, valid, upload("shared value"));
        assertThat(documents.detail(null, version.documentId()).dimensions()).containsEntry(5, Long.toString(option));
        jdbc.update("""
                UPDATE sys_param_item SET enable = 0
                WHERE code = 'DIMENSION_5' AND groupId = (SELECT id FROM sys_param_group WHERE code = 'DOC_DIMENSION')
                """);
        assertThat(documents.update(null, version.documentId(), valid).dimensions()).containsEntry(5, Long.toString(option));
        assertInvalidDimension(Map.of(5, Long.toString(option)), "error.document.dimension.disabled");
        assertInvalidDimension(Map.of(5, "not a number"), "error.document.dimension.option");
        assertInvalidDimension(Map.of(5, "x".repeat(256)), "error.document.dimension.length");
        assertInvalidDimension(Map.of(7, "value"), "error.document.dimension.slot");
        var filter = new Filter(null, null, null, null, null, Map.of(5, "-1"));
        assertThatThrownBy(() -> documents.list(null, filter)).hasMessage("error.document.request");
        var malformed = new Filter(null, null, null, null, null, Map.of(5, "not a number"));
        assertThatThrownBy(() -> documents.list(null, malformed)).hasMessage("error.document.request");
        var blank = new Metadata("Blank dimension", null, null, Map.of(5, " "));
        var clean = documents.upload(null, null, blank, upload("no dimension"));
        assertThat(documents.detail(null, clean.documentId()).dimensions()).isEmpty();
    }

    private void assertInvalidDimension(Map<Integer, String> value, String message) {
        var metadata = new Metadata("Invalid dimension", null, null, value);
        var file = upload("invalid dimension");
        assertThatThrownBy(() -> documents.upload(null, null, metadata, file))
                .isInstanceOf(BadRequestException.class).hasMessage(message);
    }

    private tools.jackson.databind.JsonNode create(String token, String name) throws Exception {
        var metadata = new MockMultipartFile("metadata", "", "application/json",
                writeJson(Map.of("name", name)).getBytes(StandardCharsets.UTF_8));
        var file = new MockMultipartFile("file", "initial.txt", "text/plain", new byte[]{1});
        return data(mockMvc.perform(multipart("/documents").file(metadata).file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))));
    }

    private long configureDimensions() {
        long slots = jdbc.queryForObject("SELECT id FROM sys_param_group WHERE code = 'DOC_DIMENSION'", Long.class);
        long products = jdbc.queryForObject("SELECT id FROM sys_param_group WHERE code = 'DOC_PRODUCT'", Long.class);
        jdbc.update("UPDATE sys_param_item SET enable = 1, value1 = 'TEXT' WHERE groupId = ? AND code = 'DIMENSION_1'",
                slots);
        jdbc.update("UPDATE sys_param_item SET enable = 1, value1 = 'SELECT', value2 = 'DOC_PRODUCT' "
                + "WHERE groupId = ? AND code = 'DIMENSION_2'", slots);
        jdbc.update("INSERT INTO sys_param_item (groupId,code,name,enable) VALUES (?, 'DOC_API_PRODUCT', '测试产品', 1)",
                products);
        return jdbc.queryForObject("SELECT id FROM sys_param_item WHERE groupId = ? AND code = 'DOC_API_PRODUCT'",
                Long.class, products);
    }

    private static Upload upload(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new Upload("验收记录.txt", "text/plain", bytes.length, new ByteArrayInputStream(bytes));
    }
}
