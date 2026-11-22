package me.lendev.flow.mail.transport;

import me.lendev.flow.mail.MailModels.Connection;
import me.lendev.flow.mail.MailModels.InboxPage;
import me.lendev.flow.mail.MailModels.InboxQuery;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RenderedMail;
import me.lendev.flow.mail.MailModels.Submission;

import java.util.List;

/**
 * 邮件接入协议的收信与提交接口。
 */
public interface MailTransport {

    /**
     * 返回此接入实现支持的邮件协议。
     */
    Provider provider();

    /**
     * 查询收件箱且保持邮件的已读状态。
     *
     * @param providerCursor 当前实现生成并由业务层验证的续页数据，首页时为空
     * @throws MailTransportException 游标失效或服务商读取失败时抛出
     */
    InboxPage read(Connection connection, InboxQuery query, String providerCursor);

    /**
     * 提交一封邮件，返回能够确认的服务商结果。
     * 提交结果不明确时返回未知状态，由调用方记录并避免重复提交。
     */
    Submission send(Connection connection, List<Recipient> recipients, RenderedMail mail);
}
