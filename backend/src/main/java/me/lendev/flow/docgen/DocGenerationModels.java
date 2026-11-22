package me.lendev.flow.docgen;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import me.lendev.flow.document.DocumentGenerationFiles.Target;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 文档生成在 HTTP、存储及初始化模块之间使用的明确数据契约。
 */
public final class DocGenerationModels {

    private DocGenerationModels() {
    }

    /**
     * 生成主体。同一份输入所属的员工或开放应用二者恰有一个。
     */
    public record Actor(Integer userId, Long appId) {
        public Actor {
            if ((userId == null) == (appId == null) || userId != null && userId <= 0 || appId != null && appId <= 0) {
                throw new IllegalArgumentException("生成主体必须是员工或应用");
            }
        }

        public static Actor user(int userId) {
            return new Actor(userId, null);
        }

        public static Actor app(long appId) {
            return new Actor(null, appId);
        }

        int fileActor() {
            return userId == null ? 0 : userId;
        }
    }

    public record TemplateSummary(String id, String code, String name, String description, boolean enabled,
                                  String currentVersionId) {
    }

    public record CurrentTemplate(String templateId, String name, String versionId, int versionNo,
                                  JsonNode inputSchema, JsonNode exampleJson, JsonNode samples) {
    }

    public record VersionView(String id, String templateId, int versionNo, String status, String templateFormat,
                              JsonNode inputSchema, JsonNode exampleJson, JsonNode renderConfig, JsonNode samples,
                              String documentId, String documentVersionId, String etag, Instant updatedAt) {
    }

    public record GenerationView(String id, String templateId, String templateVersionId, String templateName,
                                 int versionNo, String status, String errorCode, Instant createdAt, Instant finishedAt,
                                 boolean hasInput, boolean hasFile) {
    }

    public record Slice<T>(List<T> records, long total) {
    }

    public record TemplateQuery(int pageNo, int pageSize, String keyword, Boolean enabled, Boolean published) {
    }

    public record GenerationQuery(int pageNo, int pageSize, Long templateId, String status,
                                  LocalDate startDate, LocalDate endDate) {
    }

    public record FileQuery(Target type, int pageNo, int pageSize, String keyword) {
    }

    /**
     * 文档中心中的固定模板文件或已完成的生成成品。
     */
    public record FileView(Target type, String templateId, String templateVersionId, String templateName,
                           int versionNo, String generationId, String originalFileName, long sizeBytes,
                           Instant createdAt, String status, String sftpPath) {
    }

    public record CreateTemplate(String code, String name, String description) {
    }

    public record EditTemplate(String name, String description, Boolean enabled) {
    }

    public record CreatedTemplate(TemplateSummary template, VersionView version) {
    }

    public record NewVersion(String sourceVersionId) {
    }

    public record VersionInput(JsonNode inputSchema, JsonNode exampleJson, JsonNode renderConfig, JsonNode samples) {
    }

    public record GenerateInput(String templateVersionId,
                                @JsonDeserialize(using = DocGenerationJson.class) JsonNode input) {
    }

    public record PreviewInput(@JsonDeserialize(using = DocGenerationJson.class) JsonNode input) {
    }

    /**
     * 本地样例资产的模板源及发布配置，文件字节在传入和读取时均复制。
     */
    public record SeedTemplate(String code, String name, String description, byte[] file, JsonNode inputSchema,
                               JsonNode renderConfig, JsonNode samples) {
        public SeedTemplate {
            file = file == null ? null : file.clone();
        }

        @Override
        public byte[] file() {
            return file == null ? null : file.clone();
        }

        @Override
        public boolean equals(Object value) {
            if (value == null || value.getClass() != getClass()) return false;
            var other = (SeedTemplate) value;
            return Objects.equals(code, other.code)
                    && Objects.equals(name, other.name) && Objects.equals(description, other.description)
                    && Arrays.equals(file, other.file) && Objects.equals(inputSchema, other.inputSchema)
                    && Objects.equals(renderConfig, other.renderConfig) && Objects.equals(samples, other.samples);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, name, description, Arrays.hashCode(file), inputSchema, renderConfig, samples);
        }

        @Override
        public String toString() {
            String fileBytes = file == null ? "null" : Integer.toString(file.length);
            return "SeedTemplate[code=" + code + ", name=" + name + ", fileBytes=" + fileBytes + "]";
        }
    }

    record Preview(String fileName, byte[] file) {
        Preview {
            file = file == null ? null : file.clone();
        }

        @Override
        public byte[] file() {
            return file == null ? null : file.clone();
        }

        @Override
        public boolean equals(Object value) {
            if (value == null || value.getClass() != getClass()) return false;
            var other = (Preview) value;
            return Objects.equals(fileName, other.fileName) && Arrays.equals(file, other.file);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fileName, Arrays.hashCode(file));
        }

        @Override
        public String toString() {
            String fileBytes = file == null ? "null" : Integer.toString(file.length);
            return "Preview[fileName=" + fileName + ", fileBytes=" + fileBytes + "]";
        }
    }
}
