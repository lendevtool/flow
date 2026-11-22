package me.lendev.flow.openapi.admin;

import static me.lendev.flow.common.auth.PermissionCode.OPEN_API_SECRET_MANAGE;
import static me.lendev.flow.openapi.admin.OpenApiAdminAccess.actor;
import static me.lendev.flow.openapi.admin.OpenApiAdminAccess.permit;
import static me.lendev.flow.openapi.admin.OpenApiAdminModels.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理密钥版本和一次性凭据响应，所有响应禁用缓存。
 */
@RestController
@RequestMapping(OpenApiAdminRoutes.ROOT + "/apps/{appId}/secrets")
@RequiredArgsConstructor
class OpenApiCredentialController {
    private final OpenApiAdminSecrets secrets;

    /**
     * 列出应用的凭据版本及其生命周期状态。
     *
     * @param appId 归属的应用 ID
     * @return 凭据元数据列表，不含密钥值
     */
    @GetMapping
    public ResponseEntity<R<List<SecretMetadata>>> secrets(@PathVariable @Positive long appId) {
        permit(OPEN_API_SECRET_MANAGE);
        return credentialResponse(HttpStatus.OK, secrets.secrets(appId));
    }

    /**
     * 为应用生成候选凭据，响应仅交付一次密钥值。
     *
     * @param appId 归属的应用 ID
     * @return HTTP 201、候选版本信息和一次性密钥值
     */
    @PostMapping
    public ResponseEntity<R<GeneratedSecret>> generateSecret(@PathVariable @Positive long appId) {
        permit(OPEN_API_SECRET_MANAGE);
        return credentialResponse(HttpStatus.CREATED, secrets.generate(appId, actor()).response());
    }

    /**
     * 启用指定候选版本，原活跃版本按轮换规则进入过渡状态。
     *
     * @param appId     归属的应用 ID
     * @param versionNo 要激活的凭据版本号
     * @return 激活后的版本及受影响的旧版本状态
     */
    @PostMapping("/{versionNo}/activate")
    public ResponseEntity<R<SecretActivation>> activateSecret(@PathVariable @Positive long appId,
                                                              @PathVariable @Positive int versionNo) {
        permit(OPEN_API_SECRET_MANAGE);
        return credentialResponse(HttpStatus.OK, secrets.activate(appId, versionNo, actor()).response());
    }

    /**
     * 按选定方式撤销凭据，可同时废止其已签发令牌。
     *
     * @param appId     归属的应用 ID
     * @param versionNo 要撤销的凭据版本号
     * @param request   仅停止签发或同时撤销已签发令牌的方式
     * @return 撤销后的凭据版本状态
     */
    @PostMapping("/{versionNo}/revoke")
    public ResponseEntity<R<SecretMetadata>> revokeSecret(@PathVariable @Positive long appId,
                                                          @PathVariable @Positive int versionNo, @Valid @RequestBody SecretRevoke request) {
        permit(OPEN_API_SECRET_MANAGE);
        return credentialResponse(HttpStatus.OK, secrets.revoke(appId, versionNo, request.mode(), actor()).response());
    }

    private static <T> ResponseEntity<R<T>> credentialResponse(HttpStatus status, T data) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(R.ok(data));
    }
}
