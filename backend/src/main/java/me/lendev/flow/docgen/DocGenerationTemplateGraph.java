package me.lendev.flow.docgen;

import static me.lendev.flow.docgen.DocGenerationSupport.PUBLISHED;
import static me.lendev.flow.docgen.DocGenerationSupport.id;
import static me.lendev.flow.docgen.DocGenerationSupport.renderFailure;
import static me.lendev.flow.docgen.DocGenerationSupport.unavailable;
import static me.lendev.flow.document.DocumentGenerationFiles.Target.TEMPLATE_VERSION;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.docgen.entity.DocGenVersion;
import me.lendev.flow.docgen.render.DocxRenderer;
import me.lendev.flow.docgen.render.RenderFailure;
import me.lendev.flow.docgen.render.Template;
import me.lendev.flow.document.DocumentGenerationFiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 从固定文件读取模板及已发布的子模板，控制单次渲染的模板数量与文件总量。
 */
@Component
@RequiredArgsConstructor
@Slf4j
class DocGenerationTemplateGraph {
    private static final int LOADED_BYTES = 20 * 1024 * 1024;
    private static final int GRAPH_VISITS = 64;
    private final DocGenerationRepository repository;
    private final DocumentGenerationFiles files;
    private final DocxRenderer renderer;
    private final ObjectMapper json;

    byte[] copyFile(DocGenVersion version, int actor) {
        return bytes(version, actor, LOADED_BYTES);
    }

    void validateInput(DocGenVersion version, JsonNode input) {
        renderer.validateInput(version.getInputSchema(), input);
    }

    void validateForPublication(DocGenVersion version, int actor) {
        var graph = bundle(version, actor);
        try {
            renderer.validate(graph.root());
            if (version.getExampleJson() != null) {
                renderer.render(graph.root(), version.getExampleJson(), graph.children()::get);
            }
            JsonNode samples = version.getSamplesJson();
            if (samples != null) {
                for (JsonNode sample : samples) {
                    renderer.render(graph.root(), sample.path("input"), graph.children()::get);
                }
            }
        } catch (RenderFailure failure) {
            throw renderFailure(failure, true);
        }
    }

    byte[] render(DocGenVersion version, JsonNode input, int actor) {
        var graph = bundle(version, actor);
        return renderer.render(graph.root(), input, graph.children()::get);
    }

    private TemplateBundle bundle(DocGenVersion root, int actor) {
        var load = new TemplateLoad();
        Template template = resolve(root, actor, load, new HashSet<>(), 0);
        return new TemplateBundle(template, Map.copyOf(load.templates));
    }

    /**
     * 每条引用路径独立检查深度及环路，已读取的固定文件在本次渲染中复用。
     */
    private Template resolve(DocGenVersion row, int actor, TemplateLoad load, Set<Long> path, int depth) {
        if (depth > 2 || !path.add(row.getId())) {
            throw new DocGenerationFailure(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TEMPLATE", "子模板引用成环或超过两层");
        }
        if (++load.visits > GRAPH_VISITS) throw graphTooLarge();
        var config = row.getRenderConfig() == null ? json.createObjectNode() : row.getRenderConfig();
        var references = config.path("subtemplates");
        if (!references.isMissingNode() && !references.isObject()) {
            throw new DocGenerationFailure(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TEMPLATE", "子模板配置必须是对象");
        }
        for (var reference : references.properties()) {
            var target = reference.getValue().path("templateVersionId");
            long id;
            try {
                id = id(target.asString());
            } catch (DocGenerationFailure failure) {
                throw new DocGenerationFailure(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TEMPLATE", "子模板版本标识无效");
            }
            var child = repository.version(id, false);
            if (child == null || !PUBLISHED.equals(child.getStatus())) {
                throw new DocGenerationFailure(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TEMPLATE", "子模板必须是已发布版本");
            }
            resolve(child, actor, load, path, depth + 1);
        }
        path.remove(row.getId());
        return load.templates.computeIfAbsent(row.getId(), ignored -> {
            byte[] file = bytes(row, actor, load.remaining);
            load.remaining -= file.length;
            return new Template(file, row.getInputSchema(), config);
        });
    }

    private byte[] bytes(DocGenVersion row, int actor, int remaining) {
        if (row.getDocumentVersionId() == null) {
            throw new DocGenerationFailure(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TEMPLATE", "请先上传并固定模板文件");
        }
        try {
            byte[][] result = new byte[1][];
            files.download(TEMPLATE_VERSION, row.getId(), row.getDocumentId(), row.getDocumentVersionId(), actor,
                    (metadata, stream) -> {
                        if (metadata.sizeBytes() > remaining) throw graphTooLarge();
                        byte[] file = stream.readNBytes(remaining + 1);
                        if (file.length > remaining) throw graphTooLarge();
                        result[0] = file;
                    });
            if (result[0] == null) throw unavailable();
            return result[0];
        } catch (DocGenerationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            log.warn("模板文件读取失败，versionId={}，errorType={}", row.getId(), failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private DocGenerationFailure graphTooLarge() {
        return new DocGenerationFailure(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "子模板数量或模板文件总量超过限制");
    }

    private static final class TemplateLoad {
        final Map<Long, Template> templates = new HashMap<>();
        int remaining = LOADED_BYTES;
        int visits;
    }

    private record TemplateBundle(Template root, Map<Long, Template> children) {
    }
}
