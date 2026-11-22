package me.lendev.flow.mail.persistence;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.mail.MailModels.Provider;

/**
 * 邮箱连接配置和加密凭据。
 */
@Getter
@Setter
@Table(value = "sys_mail_account", camelToUnderline = false)
public class MailAccount {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String code;
    private String name;
    private Provider provider;
    private String mailboxAddress;
    private String configJson;
    private String credentialCiphertext;
    private Boolean enable;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
