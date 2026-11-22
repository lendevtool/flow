package me.lendev.flow.audit;

import java.lang.annotation.*;

/**
 * 审计入口注解：标注在业务方法上，声明该方法对应的 {@code AuditOperation}。
 *
 * <p>生效边界：只能标注可代理的公开实例方法；数据库操作必须运行在可写
 * {@code REQUIRED} 事务内且异常回滚，external 操作由外部执行器接管。
 * 不允许嵌套标注，多个动作应合并到一个聚合操作。启动期由
 * {@code AuditStartupValidator} 校验上述契约，违规将阻止应用启动。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    AuditOperation value();
}
