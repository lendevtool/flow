package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.lendev.flow.common.exception.BadRequestException;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * 解析邮件模板并在同一渲染路径中生成预览和实际邮件。
 */
@Component
public class MailTemplateRenderer {
    // 点分段无法匹配末尾的空格、制表符或右大括号，可使用占有量词避免递归回溯。
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{[ \\t]*([A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)*+)[ \\t]*}}");
    private static final int MAX_EXPANDED = 200_000;
    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true)
            .softbreak("<br />\n").build();

    /**
     * 校验主题、正文和发送方式，按出现顺序返回所需的变量名。
     *
     * @throws BadRequestException 模板语法或变量位置超出支持范围
     */
    public List<String> validate(String subjectTemplate, String bodyTemplate, SendMode sendMode) {
        return prepare(subjectTemplate, bodyTemplate, sendMode).variables();
    }

    /**
     * 按当前收件人与业务变量渲染一封邮件并校验主题头。
     */
    public RenderedMail render(String subjectTemplate, String bodyTemplate, SendMode sendMode,
                               Map<String, String> variables, Recipient recipient) {
        return render(subjectTemplate, bodyTemplate, sendMode, variables, recipient, Set.of());
    }

    /**
     * 按发送方式预先渲染整批邮件，逐封发送时保留出错收件人的提交顺序。
     */
    List<PreparedSubmission> renderForRecipients(String subjectTemplate, String bodyTemplate, SendMode sendMode,
                                                 List<Recipient> recipients, Map<String, String> variables, Set<String> optionalVariables) {
        if (sendMode == SendMode.TOGETHER) {
            return List.of(new PreparedSubmission(recipients,
                    render(subjectTemplate, bodyTemplate, sendMode, variables, null, optionalVariables)));
        }
        List<PreparedSubmission> result = new ArrayList<>(recipients.size());
        for (var recipient : recipients) {
            try {
                var mail = render(subjectTemplate, bodyTemplate, sendMode, variables, recipient, optionalVariables);
                result.add(new PreparedSubmission(List.of(recipient), mail));
            } catch (BadRequestException invalid) {
                throw new BadRequestException("第 " + (result.size() + 1) + " 位收件人：" + invalid.getMessage());
            }
        }
        return result;
    }

    RenderedMail render(String subjectTemplate, String bodyTemplate, SendMode sendMode,
                        Map<String, String> variables, Recipient recipient, Set<String> optionalVariables) {
        var template = prepare(subjectTemplate, bodyTemplate, sendMode);
        var values = variables == null ? Map.<String, String>of() : variables;
        var expansion = new Expansion(values, recipient, optionalVariables);
        String subject = replace(subjectTemplate, expansion::value);
        if (subject.isBlank() || subject.length() > MAX_SUBJECT || subject.chars().anyMatch(Character::isISOControl)) {
            throw invalid("渲染后的主题为空、过长或含控制字符");
        }
        transform(template.document(), text -> replace(text, expansion::value), target -> {
            var match = PLACEHOLDER.matcher(target);
            if (match.matches()) {
                String url = expansion.value(match.group(1));
                validateLink(url, true);
                return url;
            }
            return target;
        });
        return new RenderedMail(subject, HTML.render(template.document()));
    }

    private static Template prepare(String subjectTemplate, String bodyTemplate, SendMode sendMode) {
        if (subjectTemplate == null || subjectTemplate.isBlank() || subjectTemplate.length() > MAX_SUBJECT
                || subjectTemplate.chars().anyMatch(Character::isISOControl)) {
            throw invalid("主题为空、过长或含控制字符");
        }
        if (bodyTemplate == null || bodyTemplate.isBlank() || bodyTemplate.length() > MAX_BODY || sendMode == null) {
            throw invalid("请填写有效的正文和发送方式");
        }
        var document = MARKDOWN.parse(bodyTemplate);
        var subjectVariables = variables(subjectTemplate);
        var expected = variables(bodyTemplate);
        var located = new LinkedHashMap<String, Integer>();
        UnaryOperator<String> note = text -> {
            variables(text).forEach((name, count) -> located.merge(name, count, Integer::sum));
            return text;
        };
        transform(document, note, target -> {
            var match = PLACEHOLDER.matcher(target);
            if (match.matches()) {
                return note.apply(target);
            }
            if (match.find(0)) {
                throw invalid("链接变量必须占据完整链接地址");
            }
            validateLink(target, false);
            return target;
        });
        if (!expected.equals(located)) {
            throw invalid("请将正文变量放在文本中或用作完整链接地址");
        }
        var names = new LinkedHashSet<>(subjectVariables.keySet());
        names.addAll(expected.keySet());
        if (names.size() > MAX_VARIABLES) {
            throw invalid("模板变量数量超出上限");
        }
        for (String name : names) {
            validateVariable(name, sendMode);
        }
        return new Template(document, List.copyOf(names));
    }

    private static void validateVariable(String name, SendMode sendMode) {
        if (name.length() > 100) {
            throw invalid("变量名称过长");
        }
        if (name.startsWith("recipient.") && (sendMode == SendMode.TOGETHER
                || !List.of("recipient.name", "recipient.email").contains(name))) {
            throw invalid("本模板无法使用变量 " + name);
        }
    }

    private static Map<String, Integer> variables(String text) {
        var result = new LinkedHashMap<String, Integer>();
        PLACEHOLDER.matcher(text).results().forEach(match -> result.merge(match.group(1), 1, Integer::sum));
        return result;
    }

    private static String replace(String text, UnaryOperator<String> getValue) {
        return PLACEHOLDER.matcher(text).replaceAll(match -> Matcher.quoteReplacement(getValue.apply(match.group(1))));
    }

    private static void transform(Node document, UnaryOperator<String> text, UnaryOperator<String> destination) {
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            switch (node) {
                case Text value -> value.setLiteral(text.apply(value.getLiteral()));
                case Code value -> value.setLiteral(text.apply(value.getLiteral()));
                case FencedCodeBlock value -> value.setLiteral(text.apply(value.getLiteral()));
                case IndentedCodeBlock value -> value.setLiteral(text.apply(value.getLiteral()));
                case Image value -> throw invalid("邮件正文可使用链接，请移除 Markdown 图片");
                case Link value -> {
                    value.setDestination(destination.apply(value.getDestination()));
                    if (value.getTitle() != null) {
                        value.setTitle(text.apply(value.getTitle()));
                    }
                    transform(value, text, destination);
                }
                default -> transform(node, text, destination);
            }
        }
    }

    private static void validateLink(String target, boolean requireHttps) {
        try {
            URI uri = URI.create(target);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean web = "https".equals(scheme) || (!requireHttps && "http".equals(scheme));
            if (web && uri.getHost() != null && uri.getUserInfo() == null) {
                return;
            }
            if (!requireHttps && "mailto".equals(scheme) && !uri.getSchemeSpecificPart().isBlank()) {
                return;
            }
        } catch (IllegalArgumentException invalidUri) {
            // 保持错误内容固定，避免在告警中回显模板变量。
        }
        throw invalid(requireHttps ? "链接变量必须是有效的 HTTPS 地址" : "请填写有效的邮件链接地址");
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(message);
    }

    record PreparedSubmission(List<Recipient> recipients, RenderedMail mail) {
    }

    private record Template(Node document, List<String> variables) {
    }

    private static final class Expansion {
        private final Map<String, String> variables;
        private final Recipient recipient;
        private final Set<String> optionalVariables;
        private int size;

        private Expansion(Map<String, String> variables, Recipient recipient, Set<String> optionalVariables) {
            this.variables = variables;
            this.recipient = recipient;
            this.optionalVariables = optionalVariables;
        }

        private String value(String key) {
            String value = switch (key) {
                case "recipient.name" -> recipient == null ? null : recipient.name();
                case "recipient.email" -> recipient == null ? null : recipient.address();
                default -> variables.get(key);
            };
            if (value == null || value.isBlank()) {
                if (optionalVariables.contains(key)) {
                    return "";
                }
                throw invalid("缺少变量 " + key);
            }
            size += value.length();
            if (size > MAX_EXPANDED) {
                throw invalid("渲染结果超出长度上限");
            }
            return value;
        }
    }
}
