package me.lendev.flow.mail;

import java.util.Set;
import java.util.function.Supplier;

import me.lendev.flow.mail.MailAdminService.BoundTemplate;

/**
 * 邮件提交的模板标识及渲染配置；幂等重放时无需加载模板。
 */
record MailSendTemplate(Object key, Supplier<BoundTemplate> load, Set<String> optionalVariables) {
}
