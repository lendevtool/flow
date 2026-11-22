package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.MAX_VARIABLES;
import static me.lendev.flow.mail.MailModels.SendMode.INDIVIDUAL;
import static me.lendev.flow.mail.MailModels.SendMode.TOGETHER;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import me.lendev.flow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

class MailTemplateRendererTest {
    private final MailTemplateRenderer renderer = new MailTemplateRenderer();

    @Test
    void variablesRemainLiteralAndLinksAreEscaped() {
        var result = renderer.render("任务 {{task}}", "**{{task}}**，[打开]({{url}})", INDIVIDUAL,
                Map.of("task", "<img> *加粗*", "url", "https://example.com/tasks?a=1&b=2"), null);

        assertEquals("任务 <img> *加粗*", result.subject());
        assertTrue(result.html().contains("<strong>&lt;img&gt; *加粗*</strong>"));
        assertTrue(result.html().contains("href=\"https://example.com/tasks?a=1&amp;b=2\""));
    }

    @Test
    void rejectsAnInvalidLinkAndSubjectHeaderInjection() {
        var invalidLink = Map.of("url", "javascript:alert(1)");
        var injectedHeader = Map.of("name", "\r\nBcc: extra@example.com");

        assertThrows(BadRequestException.class,
                () -> renderer.render("主题", "[链接]({{url}})", TOGETHER, invalidLink, null));
        assertThrows(BadRequestException.class,
                () -> renderer.render("主题 {{name}}", "正文", TOGETHER, injectedHeader, null));
    }

    @Test
    void identifiesMissingVariablesAndRejectsPersonalVariablesInAGroup() {
        assertEquals(List.of("task", "recipient.name"), renderer.validate("{{task}}", "{{recipient.name}}", INDIVIDUAL));
        var missing = assertThrows(BadRequestException.class,
                () -> renderer.render("主题", "{{dueDate}}", TOGETHER, Map.of(), null));
        assertTrue(missing.getMessage().contains("dueDate"));
        assertThrows(BadRequestException.class, () -> renderer.validate("主题", "{{recipient.name}}", TOGETHER));
    }

    @Test
    void supportsCodeAndSafeStaticLinksWhileTreatingRawHtmlAsText() {
        var result = renderer.render("给 {{recipient.email}}", """
                `{{example}}` [联系](mailto:team@example.com "{{example}}")
                
                ```
                {{example}}
                ```
                
                    {{example}}
                
                <script>unsafe();</script>
                """, INDIVIDUAL, Map.of("example", "<b>"), new MailModels.Recipient("me@example.com", null));

        assertEquals("给 me@example.com", result.subject());
        assertTrue(result.html().contains("<code>&lt;b&gt;</code>"));
        assertTrue(result.html().contains("href=\"mailto:team@example.com\" title=\"&lt;b&gt;\""));
        assertTrue(result.html().contains("<pre><code>&lt;b&gt;\n</code></pre>"));
        assertTrue(result.html().contains("&lt;script&gt;"));
        assertFalse(result.html().contains("<script>"));
    }

    @Test
    void rejectsUnsupportedPlaceholderPositionsAndUnsafeUrls() {
        var invalidTarget = Map.of("target", "https://invalid link.example.com");

        assertThrows(BadRequestException.class, () -> renderer.validate("主题", "<div>{{value}}</div>", TOGETHER));
        assertThrows(BadRequestException.class, () -> renderer.validate("主题", "![图](https://example.com/image)", TOGETHER));
        assertThrows(BadRequestException.class, () -> renderer.validate("主题", "[打开](https://example.com/{{path}})", TOGETHER));
        assertThrows(BadRequestException.class, () -> renderer.validate("主题", "[打开](javascript:example)", TOGETHER));
        assertThrows(BadRequestException.class,
                () -> renderer.render("主题", "[打开]({{target}})", TOGETHER, invalidTarget, null));
    }

    @Test
    void enforcesTemplateAndRenderedSizeLimits() {
        String variables = IntStream.rangeClosed(1, MAX_VARIABLES + 1).mapToObj(i -> "{{field" + i + "}}")
                .collect(Collectors.joining(" "));
        String oversizedVariable = "{{" + "a".repeat(101) + "}}";
        String repeatedVariable = "{{value}} ".repeat(25);
        var longValue = Map.of("value", "a".repeat(10_000));

        assertThrows(BadRequestException.class, () -> renderer.validate("主题\n注入", "正文", INDIVIDUAL));
        assertThrows(BadRequestException.class, () -> renderer.validate("主题", "   ", INDIVIDUAL));
        assertThrows(BadRequestException.class, () -> renderer.validate("主题", variables, TOGETHER));
        assertThrows(BadRequestException.class, () -> renderer.validate("主题", oversizedVariable, TOGETHER));
        var result = assertThrows(BadRequestException.class,
                () -> renderer.render("主题", repeatedVariable, TOGETHER, longValue, null));
        assertTrue(result.getMessage().contains("长度上限"));
    }

    @Test
    void rejectsAnOversizedDottedVariable() {
        String body = "{{" + "a.".repeat(9_000) + "a}}";

        var error = assertThrows(BadRequestException.class, () -> renderer.validate("主题", body, TOGETHER));

        assertEquals("变量名称过长", error.getMessage());
    }

    @Test
    void recognizesAsciiPlaceholderSegmentsAndHorizontalPadding() {
        String subject = "{{  A0.b_2  }} {{é}} {{aé}} {{_hidden}} {{a.1b}} {{a..b}}";
        String body = "`{{\tb_1.B2\t}}` `{{name\u00a0}}` {{{nested}}}";

        assertEquals(List.of("A0.b_2", "b_1.B2", "nested"), renderer.validate(subject, body, INDIVIDUAL));
    }
}
