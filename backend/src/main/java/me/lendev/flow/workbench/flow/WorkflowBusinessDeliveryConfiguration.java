package me.lendev.flow.workbench.flow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 隔离可能等待远端响应的回写任务。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "workflow.business.delivery.enabled", matchIfMissing = true)
class WorkflowBusinessDeliveryConfiguration {

    /**
     * 投递专用单线程调度器，远端等待不占用公共调度线程。
     */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler workflowBusinessScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("workflow-business-");
        scheduler.setPoolSize(1);
        return scheduler;
    }
}
