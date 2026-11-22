package me.lendev.flow.common.auth.mfa.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.ToString;

/**
 * MFA 密钥版本及其发布状态。
 */
@Data
@Table("sys_mfa_key_material")
public class MfaKeyMaterial {

    @Id
    private String keyId;
    @ToString.Exclude
    private String keyCiphertext;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
