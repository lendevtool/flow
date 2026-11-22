package me.lendev.flow.audit;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 审计模块基础配置：开启定时任务与事务管理，并提供统一的 UTC 时钟 Bean。
 */
@Configuration
@EnableScheduling
// 事务切面顺序固定为 100：位于失败边界（50）之内、审计执行器（200）之外。
@EnableTransactionManagement(order = 100)
public class AuditConfiguration {

    /**
     * 提供审计模块统一使用的 UTC 时钟，保证事件时间戳与数据库存储口径一致。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    public Clock auditClock() {
        return Clock.systemUTC();
    }
}
