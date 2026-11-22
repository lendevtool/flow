package me.lendev.flow.common.auth.mfa.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.ToString;

/**
 * 账号的 MFA 绑定及加密凭据。
 */
@Data
@Table("sys_user_mfa")
public class MfaBindingRecord {

    @Id
    private Long userId;
    private String bindingId;
    private Boolean personalEnabled;
    @ToString.Exclude
    private String secretCiphertext;
    private Long lastUsedStep;
    @ToString.Exclude
    private String recoveryHashes;
    private String keyVersion;
}
