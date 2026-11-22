package me.lendev.flow.workbench.flow;

import static me.lendev.flow.audit.AuditOperation.TODO_FLOW_DEFINE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.lendev.flow.audit.AuditResultMismatchException;
import me.lendev.flow.audit.Audited;
import org.junit.jupiter.api.Test;

class FlowAuditHandlerTest {

    private final FlowAuditHandler handler = new FlowAuditHandler(null, null, null, null);

    @Test
    void refusesToStartWhenATemplateEntryDeclaresAnUnsupportedResult() throws Exception {
        var entry = Commands.class.getDeclaredMethod("create", WorkflowDefinitionModels.Write.class);
        var error = assertThrows(IllegalStateException.class, () -> handler.validate(entry));
        assertTrue(error.getMessage().contains(TODO_FLOW_DEFINE.code()));
        assertTrue(error.getMessage().contains(String.class.getName()));
        assertTrue(error.getMessage().contains(WorkflowDefinitionModels.Change.class.getName()));
    }

    @Test
    void refusesAnUnexpectedRuntimeResultWithoutLoggingTheResultValue() {
        var plan = handler.prepare(TODO_FLOW_DEFINE, new Object[0]);
        var error = assertThrows(AuditResultMismatchException.class, () -> plan.recordSuccess("unexpected value"));
        assertTrue(error.getMessage().contains(TODO_FLOW_DEFINE.code()));
        assertTrue(error.getMessage().contains(String.class.getName()));
        assertTrue(error.getMessage().contains(WorkflowDefinitionModels.Change.class.getName()));
        assertFalse(error.getMessage().contains("unexpected value"));
    }

    static class Commands {

        @Audited(TODO_FLOW_DEFINE)
        public String create(WorkflowDefinitionModels.Write request) {
            return "wrong result type";
        }
    }
}
