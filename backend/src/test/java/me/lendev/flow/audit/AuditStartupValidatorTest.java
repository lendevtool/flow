package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AuditStartupValidatorTest {

    private final AuditRegistry registry = new AuditRegistry(List.of(new AuditHandler() {
        public Set<AuditOperation> operations() {
            return Set.of(AuditOperation.PARAM_GROUP_DELETE);
        }

        public Plan prepare(AuditOperation operation, Object[] args) {
            return result -> List.of();
        }
    }));

    private void validate(String name) throws Exception {
        Method method = Commands.class.getDeclaredMethod(name);
        AuditStartupValidator.validateMethod(method, Commands.class, registry, new AnnotationTransactionAttributeSource());
    }

    @Test
    void acceptsPublicRequiredWriteMethodAndRejectsUnsafeContracts() throws Exception {
        validate("valid");
        for (String name : List.of("noTx", "readOnly", "newTx", "checkedNotRolledBack", "privateEntry", "finalEntry", "unknownHandler")) {
            assertThrows(IllegalStateException.class, () -> validate(name), name);
        }
    }

    @Test
    void duplicateAndExternalHandlerRegistrationsAreRejected() {
        var handler = registry.handler(AuditOperation.PARAM_GROUP_DELETE);
        var duplicates = List.of(handler, handler);
        assertThrows(IllegalStateException.class, () -> new AuditRegistry(duplicates));
        AuditHandler external = new AuditHandler() {
            public Set<AuditOperation> operations() {
                return Set.of(AuditOperation.LOGIN);
            }

            public Plan prepare(AuditOperation operation, Object[] args) {
                return result -> List.of();
            }
        };
        var externals = List.of(external);
        assertThrows(IllegalStateException.class, () -> new AuditRegistry(externals));
    }

    static class Commands {

        @Audited(AuditOperation.PARAM_GROUP_DELETE)
        @Transactional(rollbackFor = Exception.class)
        public void valid() {
            // 校验器只读取方法声明。
        }

        @Audited(AuditOperation.PARAM_GROUP_DELETE)
        public void noTx() {
            // 校验器只读取方法声明。
        }

        @Audited(AuditOperation.PARAM_GROUP_DELETE)
        @Transactional(readOnly = true)
        public void readOnly() {
            // 校验器只读取方法声明。
        }

        @Audited(AuditOperation.PARAM_GROUP_DELETE)
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void newTx() {
            // 校验器只读取方法声明。
        }

        @Audited(AuditOperation.PARAM_GROUP_DELETE)
        @Transactional
        public void checkedNotRolledBack() {
            // 校验器只读取方法声明。
        }

        @Audited(AuditOperation.PARAM_GROUP_DELETE)
        @Transactional(rollbackFor = Exception.class)
        private void privateEntry() {
            // 校验器只读取方法声明。
        }

        @Audited(AuditOperation.PARAM_GROUP_DELETE)
        @Transactional(rollbackFor = Exception.class)
        public final void finalEntry() {
            // 校验器只读取方法声明。
        }

        @Audited(AuditOperation.PARAM_GROUP_UPDATE)
        @Transactional(rollbackFor = Exception.class)
        public void unknownHandler() {
            // 校验器只读取方法声明。
        }
    }
}
