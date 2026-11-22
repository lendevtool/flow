package me.lendev.flow.common.auth.mfa.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * MFA 密钥轮转状态的单例记录。
 */
@Data
@Table("sys_mfa_key_rotation")
public class MfaKeyRotation {

    @Id
    private Integer id;
    private String activeKeyId;
    private LocalDateTime activeSince;
    private LocalDateTime lastCompletedAt;
    private String status;
    private String errorCode;
    private String usedVersions;
}
