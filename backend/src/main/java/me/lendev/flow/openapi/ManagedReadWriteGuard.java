package me.lendev.flow.openapi;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

/**
 * 阻止通用应用查询入口执行 MyBatis 写操作。
 */
@Component
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
class ManagedReadWriteGuard implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (ManagedReadInvocation.moduleReadActive()) {
            throw new OpenApiException(403, "READ_ONLY_REQUIRED", "The source interface attempted to write data");
        }
        return invocation.proceed();
    }
}
