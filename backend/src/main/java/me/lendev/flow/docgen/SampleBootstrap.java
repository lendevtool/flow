package me.lendev.flow.docgen;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.docgen.DocGenerationModels.SeedTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 应用就绪后在独立线程登记内置模板，使存储故障不阻塞启动。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "document.generation.samples", name = "enabled", havingValue = "true", matchIfMissing = true)
class SampleBootstrap {
    private static final String ASSETS = "document-generation-samples/";
    private static final String EMPTY_SAMPLE = "empty";
    private static final String SINGLE_SAMPLE = "single";
    private static final String MULTIPLE_SAMPLE = "multiple";
    private static final List<TemplateAsset> TEMPLATES = List.of(
            new TemplateAsset("01-fields", List.of(
                    new SampleAsset("minimal", "缺省值"), new SampleAsset("complete", "完整字段"))),
            new TemplateAsset("02-tables", List.of(
                    new SampleAsset(EMPTY_SAMPLE, "空数组"), new SampleAsset(SINGLE_SAMPLE, "单行"),
                    new SampleAsset(MULTIPLE_SAMPLE, "多行"))),
            new TemplateAsset("03-sections", List.of(
                    new SampleAsset(EMPTY_SAMPLE, "无章节"), new SampleAsset(SINGLE_SAMPLE, "单章"),
                    new SampleAsset(MULTIPLE_SAMPLE, "多章"))),
            new TemplateAsset("04-media", List.of(new SampleAsset("png", "PNG 图片"), new SampleAsset("jpeg", "JPEG 图片"))),
            new TemplateAsset("05-subtemplates/member", List.of(new SampleAsset(SINGLE_SAMPLE, "单成员"))),
            new TemplateAsset("05-subtemplates/team", List.of(
                    new SampleAsset(SINGLE_SAMPLE, "有成员"), new SampleAsset("empty-members", "空成员"))),
            new TemplateAsset("05-subtemplates", List.of(
                    new SampleAsset(EMPTY_SAMPLE, "空集合"), new SampleAsset(SINGLE_SAMPLE, "单组"),
                    new SampleAsset(MULTIPLE_SAMPLE, "多组"))),
            new TemplateAsset("06-word-layout", List.of(new SampleAsset("three-pages", "三页报告"))),
            new TemplateAsset("07-complete-report", List.of(new SampleAsset("complete", "完整报告"))));

    private final DocumentGenerationService generations;
    private final ObjectMapper json;
    private final TaskScheduler scheduler;
    private final Duration retryDelay;
    private final int maxAttempts;

    SampleBootstrap(DocumentGenerationService generations, ObjectMapper json,
                    @Qualifier("documentGenerationSampleScheduler") TaskScheduler scheduler,
                    @Value("${document.generation.samples.retry-delay:1m}") Duration retryDelay,
                    @Value("${document.generation.samples.max-attempts:3}") int maxAttempts) {
        if (retryDelay.isZero() || retryDelay.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("文档生成样例初始化的重试时间和尝试次数必须大于零");
        }
        this.generations = generations;
        this.json = json;
        this.scheduler = scheduler;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
    }

    @Bean(defaultCandidate = false)
    static ThreadPoolTaskScheduler documentGenerationSampleScheduler() {
        var worker = new ThreadPoolTaskScheduler();
        worker.setThreadNamePrefix("document-generation-samples-");
        return worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.schedule(() -> attempt(1), scheduler.getClock().instant());
    }

    private void attempt(int number) {
        try {
            seed();
        } catch (IOException | RuntimeException failure) {
            log.warn("内置文档生成样例未全部发布，请检查数据库和文档中心 SFTP，attempt={} maxAttempts={} errorType={}",
                    number, maxAttempts, failure.getClass().getSimpleName());
            if (number < maxAttempts) {
                scheduler.schedule(() -> attempt(number + 1), scheduler.getClock().instant().plus(retryDelay));
            }
        }
    }

    /**
     * 按子模板的发布顺序处理样例。已发布模板的版本由服务原样返回，供本轮的上层模板引用。
     *
     * @throws IOException 打包的样例文件不可读
     */
    void seed() throws IOException {
        Map<String, Long> versions = new HashMap<>();
        Exception firstFailure = null;
        for (var asset : TEMPLATES) {
            try {
                seed(asset, versions);
            } catch (IOException | RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                log.warn("内置模板暂未发布，asset={} errorType={}", asset.directory(), failure.getClass().getSimpleName());
            }
        }
        if (firstFailure instanceof IOException failure) throw failure;
        if (firstFailure instanceof RuntimeException failure) throw failure;
    }

    private void seed(TemplateAsset asset, Map<String, Long> versions) throws IOException {
        String directory = asset.directory();
        String base = ASSETS + directory + "/";
        JsonNode template = readJson(base + "template.json");
        ObjectNode config = (ObjectNode) template.required("renderConfig");
        resolveSubtemplates(config, template.path("sampleChildTemplates"), versions);
        var spec = new SeedTemplate(template.required("code").asString(), template.required("name").asString(),
                "内置文档生成样例", new ClassPathResource(base + "template.docx").getContentAsByteArray(),
                template.required("inputSchema"), config, inputs(base, asset.samples()));
        versions.put(directory, generations.seedTemplate(spec));
    }

    private static void resolveSubtemplates(ObjectNode config, JsonNode aliases, Map<String, Long> versions) {
        if (aliases.isMissingNode()) {
            return;
        }
        var subtemplates = config.withObjectProperty("subtemplates");
        for (var reference : aliases.properties()) {
            Long version = versions.get(reference.getValue().asString());
            if (version == null) {
                throw new IllegalStateException("内置文档生成样例引用的子模板尚未发布");
            }
            subtemplates.putObject(reference.getKey()).put("templateVersionId", version.toString());
        }
    }

    private ArrayNode inputs(String base, List<SampleAsset> assets) throws IOException {
        var result = json.createArrayNode();
        for (var asset : assets) {
            result.addObject().put("code", asset.code()).put("name", asset.name())
                    .set("input", readJson(base + asset.code() + ".json"));
        }
        return result;
    }

    private JsonNode readJson(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return json.readTree(stream);
        }
    }

    private record TemplateAsset(String directory, List<SampleAsset> samples) {
    }

    private record SampleAsset(String code, String name) {
    }
}
