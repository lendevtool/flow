package me.lendev.flow.common.auth.mfa.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * MFA 密钥发布设置的单例记录。
 */
@Data
@Table("sys_mfa_key_settings")
public class MfaKeySettings {

    @Id
    private Integer id;
    private Long revision;
    private String targetKeyId;
    private LocalDateTime publishedAt;
}
