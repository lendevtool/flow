package me.lendev.flow.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.auth.Roles;
import me.lendev.flow.common.auth.mfa.MfaKeyAdminService;
import me.lendev.flow.common.auth.mfa.MfaRotationService;
import me.lendev.flow.common.response.R;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * 认证策略的超级管理员管理入口。
 */
@RestController
@RequestMapping("/system-policy/auth-security")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService service;
    private final MfaRotationService rotation;
    private final MfaKeyAdminService keys;

    public record UpdateRequest(@NotNull @Positive Long revision, @NotNull JsonNode config) {

    }

    public record ResetRequest(@NotNull @Positive Long revision) {

    }

    /**
     * 读取当前已提交的系统认证策略。
     *
     * @return 策略配置、结构版本和修订号
     */
    @GetMapping
    @Roles(RoleType.SUPER_ADMIN)
    public R<PolicyService.View> get() {
        return R.ok(service.get(AuthPolicy.CODE));
    }

    /**
     * 校验当前修订号后，以完整配置替换系统认证策略。
     *
     * @param request 期望修订号及完整的新策略配置
     * @return 写入后的策略及最新修订号
     * @throws PolicyConflictException 当前修订号已经变化
     */
    @PutMapping
    @Roles(RoleType.SUPER_ADMIN)
    public R<PolicyService.View> update(@Valid @RequestBody UpdateRequest request) {
        return R.ok(service.update(AuthPolicy.CODE, request.revision(), request.config()));
    }

    public record MfaKeyRequest(String keyId, String key) {

        @Override
        public String toString() {
            return "MfaKeyRequest[redacted]";
        }
    }

    public record GeneratedMfaKey(String key) {

        @Override
        public String toString() {
            return "GeneratedMfaKey[redacted]";
        }
    }

    public record MfaKeyRefreshRequest(@NotNull @PositiveOrZero Long revision, String targetKeyId,
                                       @NotNull Boolean activate) {

    }

    /**
     * 查询可用的 MFA 密钥版本及当前写入目标。
     *
     * @return 密钥版本元数据与发布配置
     */
    @GetMapping("/mfa-keys")
    @Roles(RoleType.SUPER_ADMIN)
    public R<MfaKeyAdminService.View> mfaKeys() {
        return R.ok(keys.view());
    }

    /**
     * 加密保存 MFA 数据密钥草稿。
     *
     * @param request 新版本标识和待加密的数据密钥
     * @return 草稿保存后的版本元数据
     */
    @PostMapping("/mfa-keys")
    @Roles(RoleType.SUPER_ADMIN)
    public R<MfaKeyAdminService.View> stageMfaKey(@RequestBody MfaKeyRequest request) {
        keys.stage(request.keyId(), request.key());
        return R.ok(keys.view());
    }

    /**
     * 生成一个可供暂存的 MFA 数据密钥。
     *
     * @return 用于提交草稿的密钥值，生成操作不保存该值
     */
    @PostMapping("/mfa-keys/generate")
    @Roles(RoleType.SUPER_ADMIN)
    public R<GeneratedMfaKey> generateMfaKey() {
        return R.ok(new GeneratedMfaKey(keys.generate()));
    }

    /**
     * 根据当前修订号发布密钥配置，并可启动目标版本轮转。
     *
     * @param request 期望配置修订号、目标密钥版本和是否立即轮转
     * @return 刷新后的密钥版本元数据与配置
     */
    @PostMapping("/mfa-keys/refresh")
    @Roles(RoleType.SUPER_ADMIN)
    public R<MfaKeyAdminService.View> refreshMfaKeys(@Valid @RequestBody MfaKeyRefreshRequest request) {
        keys.refresh(request.revision(), request.targetKeyId(), request.activate());
        return R.ok(keys.view());
    }

    /**
     * 查询 MFA 写入版本、轮转时点及未处理绑定数量。
     *
     * @return 当前轮转进度与允许执行的操作状态
     */
    @GetMapping("/mfa-rotation")
    @Roles(RoleType.SUPER_ADMIN)
    public R<MfaRotationService.Status> mfaRotation() {
        return R.ok(rotation.status());
    }

    /**
     * 人工启动或恢复已配置目标版本的 MFA 轮转。
     *
     * @return 启动后的轮转状态
     */
    @PostMapping("/mfa-rotation/start")
    @Roles(RoleType.SUPER_ADMIN)
    public R<MfaRotationService.Status> startMfaRotation() {
        rotation.start(false);
        return R.ok(rotation.status());
    }

    /**
     * 按期望修订号将认证配置恢复为策略默认值。
     *
     * @param request 当前系统认证策略的修订号
     * @return 恢复后的策略和最新修订号
     * @throws PolicyConflictException 当前修订号已经变化
     */
    @PostMapping("/reset")
    @Roles(RoleType.SUPER_ADMIN)
    public R<PolicyService.View> reset(@Valid @RequestBody ResetRequest request) {
        return R.ok(service.reset(AuthPolicy.CODE, request.revision()));
    }
}
