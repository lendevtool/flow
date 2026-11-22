package me.lendev.flow.docgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import me.lendev.flow.document.DocumentStorage;
import me.lendev.flow.support.ApiTestBase;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"flow.open-api.enabled=true", "document.generation.samples.enabled=false",
        "document.generation.maintenance-enabled=false", "document.cleanup.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:flowOpenDocGeneration;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
class OpenDocGenerationApiTest extends ApiTestBase {
    private static final String ROOT = "/open/v1/document-generation";
    private static final String ADMIN_API = "/admin/open-api";
    private static final String ENVIRONMENT = "open-docgen-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEMPLATES = "document-generation.templates.read";
    private static final String CURRENT = "document-generation.templates.current.read";
    private static final String CREATE = "document-generation.generations.create";
    private static final String VIEW = "document-generation.generations.read";
    private static final String FILE = "document-generation.generations.file.read";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private DocumentGenerationService generations;
    @MockitoBean
    private DocumentStorage storage;

    @DynamicPropertySource
    static void isolateApplicationTokens(DynamicPropertyRegistry properties) {
        properties.add("flow.open-api.environment", () -> ENVIRONMENT);
    }

    @Test
    void publishesContractsAndEnforcesApplicationScopeAndOwnership() throws Exception {
        String admin = loginAsAdmin();
        var endpoints = publishEndpoints(admin);
        var paths = body(getJson(ADMIN_API + "/docs/v1", admin)).path("paths");
        assertScope(paths, "/templates", "get", TEMPLATES);
        assertScope(paths, "/templates/{id}/current", "get", CURRENT);
        assertScope(paths, "/generations", "post", CREATE);
        assertScope(paths, "/generations/{id}", "get", VIEW);
        assertScope(paths, "/generations/{id}/file", "get", FILE);
        assertThat(paths.path(ROOT + "/generations/{id}/file").path("get").path("responses").path("200").path("content")
                .has(DOCX)).isTrue();
        var createParameters = paths.path(ROOT + "/generations").path("post").path("parameters");
        assertThat(createParameters.valueStream().anyMatch(parameter -> "Idempotency-Key".equals(parameter.path("name").asString())
                && parameter.path("required").asBoolean())).isTrue();

        String limited = applicationToken(admin, endpoints.get(TEMPLATES));
        getJson(ROOT + "/templates", limited).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        var missing = body(getJson(ROOT + "/templates/1/current", limited), 403);
        assertThat(missing.path("code").asString()).isEqualTo("INSUFFICIENT_SCOPE");
        getJson(ROOT + "/templates", admin).andExpect(status().isUnauthorized());

        var templates = initializeSamplesWithTemporaryStorageFailure(limited);
        String caller = applicationToken(admin, endpoints.get(CURRENT), endpoints.get(CREATE), endpoints.get(VIEW), endpoints.get(FILE));
        String otherApp = applicationToken(admin, endpoints.get(CREATE), endpoints.get(VIEW), endpoints.get(FILE));
        String templateId = sampleTemplate(templates, "sample-fields").path("id").asString();
        JsonNode current = body(getJson(ROOT + "/templates/" + templateId + "/current", caller));
        assertThat(current.path("templateId").asString()).isEqualTo(templateId);
        assertThat(current.path("inputSchema").path("type").asString()).isEqualTo("object");
        assertThat(current.path("samples").size()).isEqualTo(2);

        int sampleCount = 0;
        for (var item : templates) {
            var published = body(getJson(ROOT + "/templates/" + item.path("id").asString() + "/current", caller));
            sampleCount += published.path("samples").size();
        }
        assertThat(sampleCount).isEqualTo(18);
        assertGenerationOwnership(current, caller, otherApp, admin);
    }

    private JsonNode initializeSamplesWithTemporaryStorageFailure(String token) throws Exception {
        var objects = new ConcurrentHashMap<String, byte[]>();
        var uploads = new AtomicInteger();
        doAnswer(invocation -> {
            if (uploads.incrementAndGet() == 3) {
                throw new IOException("temporary test storage failure");
            }
            objects.put(invocation.getArgument(0), invocation.getArgument(1, InputStream.class).readAllBytes());
            return null;
        }).when(storage).put(anyString(), any());
        doAnswer(invocation -> {
            try (var input = new ByteArrayInputStream(objects.get(invocation.getArgument(0)))) {
                invocation.getArgument(1, DocumentStorage.Reader.class).read(input);
            }
            return null;
        }).when(storage).read(anyString(), any());

        var scheduler = mock(TaskScheduler.class);
        var scheduled = new AtomicReference<Runnable>();
        var nextRun = new AtomicReference<Instant>();
        var now = Instant.parse("2026-11-22T00:00:00Z");
        when(scheduler.getClock()).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            scheduled.set(invocation.getArgument(0));
            nextRun.set(invocation.getArgument(1));
            return null;
        });
        var bootstrap = new SampleBootstrap(generations, objectMapper, scheduler, Duration.ofSeconds(2), 3);
        bootstrap.start();
        assertThat(uploads.get()).isZero();
        assertThat(nextRun.get()).isEqualTo(now);
        scheduled.getAndSet(null).run();
        var partiallySeeded = body(getJson(ROOT + "/templates", token));
        assertThat(partiallySeeded.size()).isEqualTo(8);
        assertThat(partiallySeeded.valueStream().map(item -> item.path("code").asString()))
                .doesNotContain("sample-sections").contains("sample-complete-report");
        assertThat(nextRun.get()).isEqualTo(now.plusSeconds(2));
        JsonNode original = sampleTemplate(partiallySeeded, "sample-fields");
        scheduled.getAndSet(null).run();
        var allTemplates = body(getJson(ROOT + "/templates", token));
        assertThat(allTemplates.size()).isEqualTo(9);
        assertThat(scheduled.get()).isNull();
        assertThat(sampleTemplate(allTemplates, "sample-fields")).isEqualTo(original);
        int completedUploads = uploads.get();
        bootstrap.seed();
        assertThat(uploads.get()).isEqualTo(completedUploads);
        return allTemplates;
    }

    private void assertGenerationOwnership(JsonNode current, String caller, String otherApp, String admin) throws Exception {
        String versionId = current.path("versionId").asString();
        JsonNode input = current.path("samples").get(0).path("input");
        String key = UUID.randomUUID().toString();
        var payload = map("templateVersionId", versionId, "input", input);
        assertThat(body(generate(caller, null, payload), 400).path("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(body(generate(caller, key, map("templateVersionId", versionId, "input", map())), 400)
                .path("code").asString()).isEqualTo("INVALID_INPUT");
        JsonNode generated = body(generate(caller, key, payload));
        String id = generated.path("id").asString();
        assertThat(generated.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(generated.path("templateVersionId").asString()).isEqualTo(versionId);
        assertThat(generated.has("input")).isFalse();
        assertThat(body(generate(caller, key, payload)).path("id").asString()).isEqualTo(id);
        JsonNode changed = current.path("samples").get(1).path("input");
        assertThat(body(generate(caller, key, map("templateVersionId", versionId, "input", changed)), 409).path("code").asString())
                .isEqualTo("REQUEST_KEY_CONFLICT");
        assertThat(body(generate(otherApp, key, payload)).path("id").asString()).isNotEqualTo(id);

        String path = ROOT + "/generations/" + id;
        assertThat(body(getJson(path, caller)).path("id").asString()).isEqualTo(id);
        assertThat(body(getJson(path, otherApp), 404).path("code").asString()).isEqualTo("GENERATION_NOT_FOUND");
        assertThat(body(getJson(path + "/file", otherApp), 404).path("code").asString()).isEqualTo("GENERATION_NOT_FOUND");
        assertThat(body(getJson("/document-generation/generations/" + id, admin), 404)
                .path("data").path("errorCode").asString())
                .isEqualTo("GENERATION_NOT_FOUND");
        var download = mockMvc.perform(get(path + "/file").accept(DOCX).header(HttpHeaders.AUTHORIZATION, bearer(caller)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, DOCX))
                .andReturn().getResponse();
        assertThat(ContentDisposition.parse(download.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename()).endsWith(".docx");
        try (var docx = new XWPFDocument(new ByteArrayInputStream(download.getContentAsByteArray()))) {
            assertThat(docx.getParagraphs()).anyMatch(paragraph -> paragraph.getText().contains("产品资料办理单"));
        }
    }

    private ResultActions generate(String token, String key, Map<String, Object> payload) throws Exception {
        var request = post(ROOT + "/generations").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(payload));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private static JsonNode sampleTemplate(JsonNode templates, String code) {
        return templates.valueStream().filter(template -> code.equals(template.path("code").asString())).findFirst().orElseThrow();
    }

    private Map<String, Long> publishEndpoints(String admin) throws Exception {
        var endpoints = data(getJson(ADMIN_API + "/endpoints", admin, "moduleCode", "document-generation"));
        Map<String, Long> ids = new HashMap<>();
        for (var endpoint : endpoints.path("pageData")) {
            long id = endpoint.path("id").asLong();
            assertThat(endpoint.path("status").asString()).isEqualTo("REGISTERED");
            assertThat(endpoint.path("dataScopeType").asString()).isEqualTo("NONE");
            data(putJson(ADMIN_API + "/endpoints/" + id + "/status", admin, map("status", "PUBLISHED", "version", 0)));
            ids.put(endpoint.path("endpointCode").asString(), id);
        }
        assertThat(ids).containsOnlyKeys(TEMPLATES, CURRENT, CREATE, VIEW, FILE);
        return ids;
    }

    private static void assertScope(JsonNode paths, String path, String method, String scope) {
        assertThat(paths.path(ROOT + path).path(method).path("x-required-scope").asString()).isEqualTo(scope);
    }

    private String applicationToken(String admin, Long... endpoints) throws Exception {
        var application = data(postJson(ADMIN_API + "/apps", admin, map("name", "文档生成接入")));
        String appPath = ADMIN_API + "/apps/" + application.path("id").asString();
        for (long id : endpoints) {
            data(putJson(appPath + "/grants/" + id, admin, map("status", "GRANTED", "version", null)));
        }
        String secret = body(postJson(appPath + "/secrets", admin, null), 201).path("data").path("clientSecret").asString();
        String credentials = application.path("clientId").asString() + ":" + secret;
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.US_ASCII));
        return body(mockMvc.perform(post("/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .formField("grant_type", "client_credentials"))).path("access_token").asString();
    }

    private ResultActions putJson(String path, String token, Object payload) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(payload)));
    }
}
