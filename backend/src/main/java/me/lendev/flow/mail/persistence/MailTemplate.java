package me.lendev.flow.mail.persistence;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.mail.MailModels.SendMode;

/**
 * 绑定邮箱的 Markdown 邮件模板。
 */
@Getter
@Setter
@Table(value = "sys_mail_template", camelToUnderline = false)
public class MailTemplate {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String code;
    private String name;
    private Long accountId;
    private String subjectTemplate;
    private String bodyTemplate;
    private String bodyFormat;
    private SendMode sendMode;
    private Integer revision;
    private Boolean enable;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
