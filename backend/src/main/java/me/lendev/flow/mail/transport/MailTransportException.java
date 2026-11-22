package me.lendev.flow.mail.transport;

/**
 * 已脱敏的邮件接入异常，内容可用于业务错误响应。
 */
public final class MailTransportException extends RuntimeException {

    private final String code;

    MailTransportException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回稳定的业务错误码。
     */
    public String code() {
        return code;
    }

    static MailTransportException invalidCursor() {
        return new MailTransportException("MAIL_INBOX_CURSOR_INVALID", "邮件查询游标无效或已失效");
    }
}
