package me.lendev.flow.mail.persistence;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.mail.MailModels.Status;

/**
 * 一次服务商提交的收件人与结果。
 */
@Getter
@Setter
@Table(value = "sys_mail_send_detail", camelToUnderline = false)
public class MailSendDetail {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long sendLogId;
    private Integer sequenceNo;
    private String recipientsJson;
    private String recipientResultsJson;
    private Status status;
    private String providerRequestId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
