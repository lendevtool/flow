package me.lendev.flow.mail.mapper;

import com.mybatisflex.core.BaseMapper;
import me.lendev.flow.mail.persistence.MailSendLog;

/**
 * 操作数据库中的一次邮件提交请求的历史快照
 */
public interface MailSendLogMapper extends BaseMapper<MailSendLog> {
}
