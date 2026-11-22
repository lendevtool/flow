package me.lendev.flow.openapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.bind.annotation.BindParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;

class ManagedReadInputsTest {
    @Test
    void excludesQueryRecordsWithAlternateBindingNames() throws NoSuchMethodException {
        var handler = new HandlerMethod(new Controller(), Controller.class.getMethod("list", RenamedQuery.class));
        assertNull(ManagedReadInputs.describe("/items", handler));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/items", "/a_B-0", "/a/b/c"})
    void acceptsLiteralSourceSegments(String path) throws NoSuchMethodException {
        var handler = new HandlerMethod(new Controller(), Controller.class.getMethod("read"));
        assertNotNull(ManagedReadInputs.describe(path, handler));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "items", "//items", "/items/", "/items//detail", "/items/{id",
            "/items/{1id}", "/items/{item-id}", "/项目", "/items/?", "/items/a.b"})
    void rejectsMalformedSourceSegments(String path) throws NoSuchMethodException {
        var handler = new HandlerMethod(new Controller(), Controller.class.getMethod("read"));
        assertNull(ManagedReadInputs.describe(path, handler));
    }

    @Test
    void acceptsBoundPathVariablesAndRejectsDuplicateBindings() throws NoSuchMethodException {
        var handler = new HandlerMethod(new Controller(), Controller.class.getMethod("detail", long.class));
        assertNotNull(ManagedReadInputs.describe("/items/{itemId}", handler));
        assertNull(ManagedReadInputs.describe("/items/{itemId}/{itemId}", handler));
        assertNull(ManagedReadInputs.describe("/items/{otherId}", handler));
    }

    @Test
    void validatesLongSourcePathsWithoutRecursiveMatching() throws NoSuchMethodException {
        var handler = new HandlerMethod(new Controller(), Controller.class.getMethod("read"));
        String segments = "/items".repeat(10_000);
        assertNotNull(ManagedReadInputs.describe(segments, handler));
        assertNull(ManagedReadInputs.describe(segments + "/", handler));
        assertNotNull(ManagedReadInputs.describe("/" + "a".repeat(100_000), handler));
    }

    @Test
    void preservesPublicSlugSyntaxAndLengthLimits() {
        String prefix = ManagedReadRegistry.PATH_PREFIX;
        assertTrue(ManagedReadRegistry.isPublicPath(prefix + "a0-b1"));
        assertTrue(ManagedReadRegistry.isPublicPath(prefix + "a".repeat(256 - prefix.length())));
        assertFalse(ManagedReadRegistry.isPublicPath(prefix + "a".repeat(257 - prefix.length())));
        assertFalse(ManagedReadRegistry.isPublicPath(prefix + "a-"));
        assertFalse(ManagedReadRegistry.isPublicPath(prefix + "a--b"));
        assertFalse(ManagedReadRegistry.isPublicPath(prefix + "A"));
        assertFalse(ManagedReadRegistry.isPublicPath(prefix + "a_b"));
        assertFalse(ManagedReadRegistry.isPublicPath(null));
    }

    static class Controller {
        public void read() {
            // 仅提供无参数的来源方法签名，路径校验不需要业务实现。
        }

        public void detail(@PathVariable("itemId") long itemId) {
            // 仅提供具名路径变量的来源方法签名，路径校验不需要业务实现。
        }

        public void list(@Valid RenamedQuery query) {
            // 仅作为反射解析参数注解的测试入口，无需执行业务逻辑。
        }
    }

    record RenamedQuery(@BindParam("search") String keyword) {
    }
}
