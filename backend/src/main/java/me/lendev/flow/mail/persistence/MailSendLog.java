package me.lendev.flow.mail.persistence;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.SendMode;
import me.lendev.flow.mail.MailModels.Status;

/**
 * 一次邮件提交请求的历史快照。
 */
@Getter
@Setter
@Table(value = "sys_mail_send_log", camelToUnderline = false)
public class MailSendLog {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long accountId;
    private Long templateId;
    private String accountCode;
    private String templateCode;
    private Integer templateRevision;
    private String senderAddress;
    private Provider provider;
    private SendMode sendMode;
    private String callerKey;
    private String requestId;
    private String idempotencyKey;
    private String requestDigest;
    private Integer recipientCount;
    private Status status;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
