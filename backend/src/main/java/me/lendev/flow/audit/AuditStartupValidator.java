package me.lendev.flow.audit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.query.mapper.AuditQueryMapper;
import me.lendev.flow.common.response.R;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 审计启动校验器：应用启动完成时校验审计表已就绪、入口方法可代理且事务契约合规、 AOP 切面顺序为"失败边界→事务→审计执行器"。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditStartupValidator implements SmartInitializingSingleton {

    private final ConfigurableListableBeanFactory beans;
    private final AuditRegistry registry;
    private final TransactionAttributeSource transactions;
    private final ObjectProvider<AuditExternalExecutor> external;
    private final AuditQueryMapper queries;

    /**
     * 所有单例初始化完成后执行审计契约校验：审计表就绪、入口方法可代理且事务合规、
     * 切面顺序正确。任一校验失败将抛出异常阻止应用启动。
     */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            queries.probeTables();
        } catch (DataAccessException error) {
            throw new IllegalStateException("审计表尚未就绪，请先执行 db/migration/2026-11-06-operation-audit.sql 增量迁移");
        }
        int entries = 0;
        for (String name : beans.getSingletonNames()) {
            Object bean = beans.getSingleton(name);
            if (bean != null) {
                entries += validateBean(bean);
            }
        }
        log.info("审计启动校验通过，审计入口方法数={}", entries);
    }

    /**
     * 校验单个 Bean 上的全部审计入口：方法契约、外部执行器可用性与代理链顺序。
     *
     * @return 该 Bean 上通过校验的审计入口方法数量
     */
    private int validateBean(Object bean) {
        Class<?> type = AopUtils.getTargetClass(bean);
        if (!type.getName().startsWith("me.lendev.flow.") || type.isAnnotationPresent(Aspect.class)) {
            return 0;
        }
        int count = 0;
        for (Method method : MethodIntrospector.selectMethods(type,
                (org.springframework.util.ReflectionUtils.MethodFilter) candidate -> candidate.isAnnotationPresent(Audited.class))) {
            validateMethod(method, type, registry, transactions);
            AuditOperation operation = method.getAnnotation(Audited.class).value();
            if (operation.external() && external.getIfAvailable() == null) {
                throw new IllegalStateException("外部操作审计执行器未配置");
            }
            validateAdvisors(bean, !operation.external());
            count++;
        }
        return count;
    }

    /**
     * 校验审计入口方法契约：必须为可代理的公开实例方法；数据库操作须使用可写
     * {@code REQUIRED} 事务、异常回滚且返回值不是统一响应包装，并通过 Handler 自定义校验。
     *
     * @param method       被 {@code @Audited} 标注的入口方法
     * @param type         入口方法所属的目标类
     * @param registry     审计处理器注册表
     * @param transactions 事务属性源，用于解析方法的事务契约
     * @throws IllegalStateException 方法不满足审计契约时抛出
     */
    static void validateMethod(Method method, Class<?> type, AuditRegistry registry, TransactionAttributeSource transactions) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
            throw new IllegalStateException("审计入口必须为可代理的公开实例方法: " + method.getName());
        }
        AuditOperation operation = method.getAnnotation(Audited.class).value();
        if (operation.external()) {
            return;
        }
        var transaction = transactions.getTransactionAttribute(method, type);
        if (transaction == null || transaction.isReadOnly()
                || transaction.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || !transaction.rollbackOn(new Exception()) || R.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("审计入口须使用可写REQUIRED事务、异常回滚及明确结果契约: " + method.getName());
        }
        registry.handler(operation).validate(method);
    }

    /**
     * 校验代理链顺序：失败边界(50) → 事务(100) → 审计执行器(200)。
     *
     * @param bean     审计入口所在的 Spring Bean
     * @param database 是否为数据库操作；数据库操作额外要求事务切面位于边界与执行器之间
     * @throws IllegalStateException Bean 未被代理或切面顺序错误时抛出
     */
    private void validateAdvisors(Object bean, boolean database) {
        if (!(bean instanceof Advised advised)) {
            throw new IllegalStateException("审计入口未被Spring代理");
        }
        int boundary = -1;
        int transaction = -1;
        int audit = -1;
        var advisors = advised.getAdvisors();
        for (int i = 0; i < advisors.length; i++) {
            var advice = advisors[i].getAdvice();
            if (advice instanceof TransactionInterceptor) {
                transaction = i;
            }
            if (advice instanceof AspectJAroundAdvice around) {
                Class<?> owner = around.getAspectJAdviceMethod().getDeclaringClass();
                if (owner == AuditBoundaryAspect.class) {
                    boundary = i;
                } else if (owner == AuditAspect.class) {
                    audit = i;
                }
            }
        }
        // 代理链顺序必须为：失败边界(50) → 事务(100) → 审计执行器(200)，否则失败记录会先于回滚发生。
        if (boundary < 0 || audit < 0 || boundary >= audit
                || database && (transaction <= boundary || transaction >= audit)) {
            throw new IllegalStateException("审计代理顺序错误，应为失败边界→事务→审计执行器");
        }
    }
}
