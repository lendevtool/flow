package me.lendev.flow.pms.domain.dto;

import java.util.List;

import lombok.Data;
import me.lendev.flow.audit.AuditExternalMetadata;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.common.auth.mfa.MfaService;
import lombok.ToString;

/**
 * 登录响应token
 *
 * @author lendev
 */
@Data
public class LoginTokenDto implements AuditExternalMetadata {

    /**
     * 通过登录获取的token
     */
    @ToString.Exclude
    private String accessToken;

    private boolean passwordChangeRequired;

    @ToString.Exclude
    private MfaService.Challenge mfaChallenge;

    @ToString.Exclude
    private List<String> recoveryCodes;

    @Override
    public List<AuditChange> auditChanges() {
        return List.of();
    }

    @Override
    public String auditSummary() {
        if (mfaChallenge != null) {
            return "第一因素通过，等待 MFA";
        }
        if (recoveryCodes == null) {
            return "登录";
        }
        return recoveryCodes.isEmpty() ? "MFA 登录验证通过" : "MFA 首次绑定并登录";
    }

}
