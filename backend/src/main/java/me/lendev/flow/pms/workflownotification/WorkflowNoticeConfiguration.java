package me.lendev.flow.pms.workflownotification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 隔离可能等待邮件服务响应的流程通知定时任务。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "workflow.notification.delivery.enabled", matchIfMissing = true)
class WorkflowNoticeConfiguration {

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler workflowNoticeScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("workflow-notice-");
        return scheduler;
    }
}
