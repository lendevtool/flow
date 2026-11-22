package me.lendev.flow.institution;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * 机构档案及变更申请的接口模型。
 */
public final class InstitutionModels {

    private InstitutionModels() {
    }

    public record Institution(long id, String code, String nameZh, String nameEn, long typeItemId,
                              long countryRegionItemId, String registrationId, String shortNameZh,
                              String shortNameEn, String officeAddress, String website, String remark,
                              boolean enabled, long version, int createdBy, int updatedBy,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record Save(@NotBlank @Size(max = 64) String code,
                       @NotBlank @Size(max = 200) String nameZh, @Size(max = 200) String nameEn,
                       @NotNull @Positive Long typeItemId, @NotNull @Positive Long countryRegionItemId,
                       @Size(max = 100) String registrationId, @Size(max = 100) String shortNameZh,
                       @Size(max = 100) String shortNameEn, @Size(max = 500) String officeAddress,
                       @Size(max = 500) String website, @Size(max = 1000) String remark,
                       @PositiveOrZero Long version) {
    }

    public record Query(@Min(1) Integer pageNo, @Min(1) @Max(100) Integer pageSize, @Size(max = 200) String keyword,
                        @Positive Long typeItemId, @Positive Long countryRegionItemId, Boolean enabled) {
    }

    public record StatusEdit(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long version) {
    }

    public record Option(long id, String code, String name, String nameEn, boolean enabled) {
    }

    public record ApprovalOptions(List<String> types, List<String> countries, List<String> fields) {
    }

    public record FieldOption(String name, String nameEn, boolean required, Integer maxLength) {
    }

    public record Options(List<Option> types, List<Option> countries, ApprovalOptions approvalOptions,
                          Map<String, FieldOption> fieldDefinitions) {
    }

    public record Submit(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 500) String reason,
                         @NotEmpty @Size(max = 5) Map<String, JsonNode> fields) {
    }

    public enum ChangeStatus {PENDING, APPLIED, CONFLICT, CANCELLED}

    public record Change(long id, long institutionId, long flowInstanceId, ChangeStatus status,
                         JsonNode baseValues, LocalDateTime resolvedAt) {
    }
}
