package me.lendev.flow.audit;

import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审计处理器注册表：启动时校验数据库操作与 Handler 一一对应，运行期按操作查找 Handler。
 */
@Component
@Slf4j
public class AuditRegistry {

    private final Map<AuditOperation, AuditHandler> handlers = new EnumMap<>(AuditOperation.class);

    /**
     * 收集全部处理器并建立操作到 Handler 的映射。
     *
     * @param providers Spring 容器中的全部审计处理器
     * @throws IllegalStateException external 操作注册了数据库 Handler，或同一操作重复注册时抛出
     */
    public AuditRegistry(List<AuditHandler> providers) {
        for (AuditHandler handler : providers) {
            for (AuditOperation operation : handler.operations()) {
                // external 操作走外部执行器，不允许注册数据库 Handler；同一操作也不允许重复注册。
                if (operation.external() || handlers.putIfAbsent(operation, handler) != null) {
                    throw new IllegalStateException("审计操作注册重复或模式不匹配: " + operation.code());
                }
            }
        }
        log.info("审计处理器注册完成，数据库操作数={}", handlers.size());
    }

    /**
     * 按操作查找已注册的处理器。
     *
     * @param operation 数据库审计操作
     * @return 负责该操作的处理器
     * @throws IllegalStateException 操作未注册 Handler 时抛出
     */
    public AuditHandler handler(AuditOperation operation) {
        AuditHandler handler = handlers.get(operation);
        if (handler == null) {
            throw new IllegalStateException("审计操作未注册Handler: " + operation.code());
        }
        return handler;
    }
}
