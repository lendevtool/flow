package me.lendev.flow.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 为可能阻塞的 SFTP 清理提供独立调度线程。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "document.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
class DocumentCleanupConfiguration {

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler documentCleanupScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("document-cleanup-");
        scheduler.setPoolSize(1);
        return scheduler;
    }
}
