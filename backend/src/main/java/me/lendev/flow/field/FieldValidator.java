package me.lendev.flow.field;

import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.field.FieldModels.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 校验字段定义及可复用的单字段取值约束。
 */
@Component
public class FieldValidator {

    private static final Pattern ATTACHMENT_ID = Pattern.compile("[1-9]\\d{0,18}");
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d{1,19}");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d{1,20}(?:\\.\\d{1,18})?");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)++");
    private static final String CHINESE_PUNCTUATION = "，。！？、；：“”‘’《》〈〉（）【】「」『』…—·";
    private static final String ENGLISH_PUNCTUATION = ",.!?;:'\"()[]{}";
    private static final int MAX_DEFAULT_LENGTH = 16384;
    private static final int OPTION_EXAMPLES_LIMIT = 10;
    private static final String REQUIRED = "required";
    private static final String VALIDATION = "error.field.validation.";
    private static final String INVALID_NUMBER = "error.field.number";

    /**
     * 校验规则与类型匹配，并确保已配置的默认值满足规则。
     *
     * @param field          待保存的字段定义
     * @param allowedOptions 枚举默认值可使用的编码集合
     * @throws BadRequestException 规则不适用、范围不合法或默认值无效
     */
    public void definition(Config field, Set<String> allowedOptions) {
        checkApplicable(field);
        checkBounds(field);
        var value = field.defaultValue();
        if (value != null && !value.isNull()) {
            if (field.dataType() == DataType.ATTACHMENT) {
                throw localized("error.field.attachment.default");
            }
            if (value.toString().length() > MAX_DEFAULT_LENGTH) {
                throw localized("error.field.default.size");
            }
            value(field, value, allowedOptions);
        }
    }

    private static void checkBounds(Config field) {
        switch (field.dataType()) {
            case TEXT -> {
                range(field.textMinLength(), field.textMaxLength());
                compile(field.textPattern());
                if (Boolean.TRUE.equals(field.textCharLimitEnabled()) && !selectedCharacters(field)) {
                    throw localized("error.field.characters.empty");
                }
            }
            case INTEGER -> {
                var min = integerBound(field.integerMin());
                var max = integerBound(field.integerMax());
                range(min, max);
                positiveUpperBound(field, max == null ? 1 : Long.signum(max));
            }
            case DECIMAL -> validateDecimalRange(field);
            case DATE -> {
                dateBounds(field.dateMin(), field.dateMax());
                range(field.dateMin(), field.dateMax());
            }
            case DATETIME -> {
                if (!validDateTime(field.dateTimeMin()) || !validDateTime(field.dateTimeMax())) {
                    throw localized("error.field.date");
                }
                range(field.dateTimeMin(), field.dateTimeMax());
            }
            case ENUM_MULTI -> range(field.enumMinItems(), field.enumMaxItems());
            case BOOLEAN, ENUM, ATTACHMENT -> {
                // 这些类型没有需要比较的上下限。
            }
        }
    }

    /**
     * 按字段定义校验单个值。整数和小数以 JSON 字符串传入，以保留精度。
     *
     * @param field          已校验的字段定义
     * @param value          用户输入，缺省时传 {@code null}
     * @param allowedOptions 当前上下文允许使用的枚举编码
     * @throws BadRequestException 值违反规则时，携带第一条失败规则及提示参数
     */
    public void value(Config field, JsonNode value, Set<String> allowedOptions) {
        value(field, value, allowedOptions, field.required());
    }

    /**
     * 使用业务节点的必填配置校验值，其他约束继续取自字段定义。
     *
     * @param field          已校验的字段定义
     * @param value          用户输入，缺省时传 {@code null}
     * @param allowedOptions 当前上下文允许使用的枚举编码
     * @param required       当前业务节点是否要求填写
     * @throws BadRequestException 值违反规则时，携带第一条失败规则及提示参数
     */
    public void value(Config field, JsonNode value, Set<String> allowedOptions, boolean required) {
        if (value == null || value.isNull()) {
            check(!required, REQUIRED);
            return;
        }
        switch (field.dataType()) {
            case TEXT -> text(field, string(value, "type.text"), required);
            case INTEGER ->
                    integerValue(field, integer(string(value, "type.integer"), "error.field.validation.integer.format"));
            case DECIMAL ->
                    decimalValue(field, decimal(string(value, "type.decimal"), "error.field.validation.decimal.format"));
            case BOOLEAN -> check(value.isBoolean(), "type.boolean");
            case DATE -> dateValue(field, parseDate(value, "date", LocalDate::parse));
            case DATETIME -> dateTimeValue(field, parseDate(value, "datetime", LocalDateTime::parse));
            case ENUM -> single(value, allowedOptions);
            case ENUM_MULTI -> multi(field, value, allowedOptions, required);
            case ATTACHMENT -> attachments(value, required);
        }
    }

    private static void attachments(JsonNode value, boolean required) {
        check(value.isArray(), "type.attachment");
        check(!required || !value.isEmpty(), REQUIRED);
        Set<String> documents = new HashSet<>();
        for (JsonNode attachment : value) {
            var documentId = attachment.path("documentId");
            var versionId = attachment.path("versionId");
            check(attachment.isObject() && attachment.size() == 2 && validAttachmentId(documentId)
                    && validAttachmentId(versionId), "attachment.reference");
            check(documents.add(documentId.asString()), "attachment.duplicate");
        }
    }

    private static boolean validAttachmentId(JsonNode value) {
        if (!value.isString()) {
            return false;
        }
        String id = value.asString();
        String limit = Long.toString(Long.MAX_VALUE);
        return ATTACHMENT_ID.matcher(id).matches() && (id.length() < limit.length() || id.compareTo(limit) <= 0);
    }

    private static String string(JsonNode value, String rule) {
        check(value.isString(), rule);
        return value.asString();
    }

    private static void check(boolean valid, String rule, Object... arguments) {
        if (!valid) {
            throw localized(VALIDATION + rule, arguments);
        }
    }

    private static DefaultMessageSourceResolvable detail(String key, Object... arguments) {
        return new DefaultMessageSourceResolvable(new String[]{VALIDATION + key}, arguments);
    }

    private static void checkApplicable(Config f) {
        var type = f.dataType();
        if (misplacedTextRules(f)
                || (Boolean.TRUE.equals(f.numberPositiveOnly()) && type != DataType.INTEGER && type != DataType.DECIMAL)
                || (type != DataType.INTEGER && present(f.integerMin(), f.integerMax()))
                || (type != DataType.DECIMAL && present(f.decimalMin(), f.decimalMax(), f.decimalPrecision(), f.decimalScale()))
                || (type != DataType.DATE && present(f.dateMin(), f.dateMax()))
                || (type != DataType.DATETIME && present(f.dateTimeMin(), f.dateTimeMax()))
                || (type != DataType.ENUM_MULTI && present(f.enumMinItems(), f.enumMaxItems()))
                || (type.isEnum() != (f.paramGroupId() != null))) {
            throw localized("error.field.rules.type");
        }
    }

    private static boolean misplacedTextRules(Config f) {
        boolean text = f.dataType() == DataType.TEXT;
        boolean limitCharacters = Boolean.TRUE.equals(f.textCharLimitEnabled());
        return (!text && (present(f.textMinLength(), f.textMaxLength(), f.textFormat(), f.textPattern())
                || limitCharacters))
                || (selectedCharacters(f) && (!limitCharacters || !text));
    }

    private static boolean present(Object... values) {
        return java.util.Arrays.stream(values).anyMatch(Objects::nonNull);
    }

    private static boolean selectedCharacters(Config f) {
        return Arrays.stream(TextCharacterClass.values()).anyMatch(category -> category.enabled.test(f));
    }

    private static void text(Config f, String value, boolean required) {
        check(!required || !value.isBlank(), REQUIRED);
        int length = value.codePointCount(0, value.length());
        check(f.textMinLength() == null || length >= f.textMinLength(), "text.min", f.textMinLength(), length);
        check(f.textMaxLength() == null || length <= f.textMaxLength(), "text.max", f.textMaxLength(), length);
        if (Boolean.TRUE.equals(f.textCharLimitEnabled())) {
            checkCharacters(f, value);
        }
        if (f.textFormat() != null) {
            check(format(f.textFormat(), value), f.textFormat() == TextFormat.EMAIL ? "text.email" : "text.url");
        }
        Pattern pattern = compile(f.textPattern());
        check(pattern == null || matches(pattern, value), "text.pattern", f.textPattern());
    }

    private static void checkCharacters(Config f, String value) {
        var classes = Arrays.stream(TextCharacterClass.values()).filter(category -> category.enabled.test(f)).toList();
        var codePoints = value.codePoints().iterator();
        for (int position = 1; codePoints.hasNext(); position++) {
            int ch = codePoints.nextInt();
            if (!allowed(classes, ch)) {
                var labels = classes.stream().map(category -> category.label)
                        .reduce((left, right) -> detail("text.characters.join", left, right))
                        .orElseGet(() -> detail("text.characters.none"));
                throw localized(VALIDATION + "text.characters", position, labels, describeRejectedCharacter(ch));
            }
        }
    }

    private static DefaultMessageSourceResolvable describeRejectedCharacter(int ch) {
        String name = switch (ch) {
            case ' ' -> "plainSpace";
            case '\t' -> "tab";
            case '\n' -> "lineBreak";
            case '\r' -> "carriageReturn";
            case 0x00A0 -> "nonBreakingSpace";
            case 0x3000 -> "fullWidthSpace";
            default -> null;
        };
        if (name != null) {
            return detail("text.characters.named", detail("text.characters." + name));
        }
        var actualClass = Arrays.stream(TextCharacterClass.values())
                .filter(category -> category.includes.test(ch))
                .findFirst().map(category -> category.label)
                .orElseGet(() -> detail("text.characters.other"));
        String character = Character.isLetterOrDigit(ch) || (ch >= '!' && ch <= '~')
                ? Character.toString(ch) : "U+%04X".formatted(ch);
        return detail("text.characters.classified", character, actualClass);
    }

    private static boolean allowed(List<TextCharacterClass> classes, int ch) {
        for (var category : classes) {
            if (category.includes.test(ch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每个类别的配置开关、取值规则和提示名称使用同一映射。
     */
    private enum TextCharacterClass {
        CHINESE(Config::textAllowChinese,
                ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN && Character.isLetter(ch), "chinese"),
        ENGLISH(Config::textAllowEnglish, ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'), "english"),
        DIGITS(Config::textAllowDigits, ch -> ch >= '0' && ch <= '9', "digits"),
        SPACE(Config::textAllowSpace, ch -> ch == ' ', "space"),
        SEPARATORS(Config::textAllowSeparators, ch -> ch == '-' || ch == '_', "separators"),
        CHINESE_MARKS(Config::textAllowChinesePunctuation, ch -> CHINESE_PUNCTUATION.indexOf(ch) >= 0,
                "chinesePunctuation", CHINESE_PUNCTUATION),
        ENGLISH_MARKS(Config::textAllowEnglishPunctuation, ch -> ENGLISH_PUNCTUATION.indexOf(ch) >= 0,
                "englishPunctuation", ENGLISH_PUNCTUATION);

        private final Predicate<Config> enabled;
        private final IntPredicate includes;
        private final DefaultMessageSourceResolvable label;

        TextCharacterClass(Predicate<Config> enabled, IntPredicate includes, String label, Object... arguments) {
            this.enabled = enabled;
            this.includes = includes;
            this.label = detail("text.characters." + label, arguments);
        }
    }

    private static boolean format(TextFormat format, String value) {
        if (format == TextFormat.EMAIL) {
            return value.length() <= 254 && EMAIL.matcher(value).matches();
        }
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Pattern compile(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw localized("error.field.pattern");
        }
    }

    private static boolean matches(Pattern pattern, String value) {
        try {
            return pattern.matcher(new BoundedText(value)).matches();
        } catch (RegexBudgetExceeded | StackOverflowError e) {
            throw localized("error.field.pattern.complex");
        }
    }

    /**
     * 限制管理员配置的正则对单个文本反复回溯。
     */
    private static final class BoundedText implements CharSequence {

        private final String value;
        private int remaining = 1_000_000;

        BoundedText(String value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining < 0) {
                throw new RegexBudgetExceeded();
            }
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return value.substring(start, end);
        }
    }

    private static final class RegexBudgetExceeded extends RuntimeException {

    }

    private static long integer(String value, String error) {
        if (INTEGER.matcher(value).matches()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw localized(error);
            }
        }
        throw localized(error);
    }

    private static Long integerBound(String value) {
        return value == null ? null : integer(value, INVALID_NUMBER);
    }

    private static BigDecimal decimal(String value, String error) {
        if (!DECIMAL.matcher(value).matches()) {
            throw localized(error);
        }
        return new BigDecimal(value);
    }

    private static void integerValue(Config f, long value) {
        check(!f.numberPositiveOnly() || value > 0, "number.positive");
        checkValueRange(value, integerBound(f.integerMin()), integerBound(f.integerMax()));
    }

    private static void decimalValue(Config f, BigDecimal value) {
        int scale = f.decimalScale() == null ? 18 : f.decimalScale();
        int precision = f.decimalPrecision() == null ? 38 : f.decimalPrecision();
        int integerDigits = Math.max(0, value.precision() - value.scale());
        int integerLimit = f.decimalPrecision() != null && f.decimalScale() != null
                ? precision - scale : 20;
        check(!f.numberPositiveOnly() || value.signum() > 0, "number.positive");
        check(value.scale() <= scale, "decimal.scale", scale);
        check(value.precision() <= precision, "decimal.precision", precision);
        check(integerDigits <= integerLimit, "decimal.integerDigits", integerLimit);
        checkValueRange(value, decimalBound(f.decimalMin()), decimalBound(f.decimalMax()));
    }

    private static void validateDecimalRange(Config f) {
        if (f.decimalPrecision() != null && f.decimalScale() != null && f.decimalScale() > f.decimalPrecision()) {
            throw localized("error.field.scale");
        }
        var min = decimalBound(f.decimalMin());
        var max = decimalBound(f.decimalMax());
        range(min, max);
        positiveUpperBound(f, max == null ? 1 : max.signum());
    }

    private static BigDecimal decimalBound(String value) {
        return value == null ? null : decimal(value, INVALID_NUMBER);
    }

    private static void positiveUpperBound(Config field, int sign) {
        if (Boolean.TRUE.equals(field.numberPositiveOnly()) && sign <= 0) {
            throw localized("error.field.positive.range");
        }
    }

    private static <T> T parseDate(JsonNode value, String type, Function<String, T> parse) {
        String input = string(value, "type." + type);
        try {
            return parse.apply(input);
        } catch (DateTimeParseException e) {
            throw localized(VALIDATION + type + ".format");
        }
    }

    private static void dateValue(Config f, LocalDate value) {
        check(validDate(value), "date.year");
        checkValueRange(value, f.dateMin(), f.dateMax());
    }

    private static void dateTimeValue(Config f, LocalDateTime value) {
        check(validDate(value.toLocalDate()), "date.year");
        check(hasMillisecondPrecision(value), "datetime.precision");
        checkValueRange(value, f.dateTimeMin(), f.dateTimeMax());
    }

    private static boolean validDate(LocalDate value) {
        return value == null || value.getYear() >= 1000 && value.getYear() <= 9999;
    }

    private static boolean validDateTime(LocalDateTime value) {
        return value == null || validDate(value.toLocalDate()) && hasMillisecondPrecision(value);
    }

    private static boolean hasMillisecondPrecision(LocalDateTime value) {
        return value.getNano() % 1_000_000 == 0;
    }

    private static void dateBounds(LocalDate min, LocalDate max) {
        if (!validDate(min) || !validDate(max)) {
            throw localized("error.field.date");
        }
    }

    private static void single(JsonNode value, Set<String> allowedOptions) {
        if (!allowedOptions.contains(string(value, "type.enum"))) {
            throw localized(VALIDATION + "enum.option", optionExamples(allowedOptions));
        }
    }

    private static void multi(Config field, JsonNode value, Set<String> allowedOptions, boolean required) {
        check(value.isArray(), "type.enumMulti");
        check(!required || !value.isEmpty(), REQUIRED);
        int count = value.size();
        check(field.enumMinItems() == null || count >= field.enumMinItems(), "enum.min", field.enumMinItems(), count);
        check(field.enumMaxItems() == null || count <= field.enumMaxItems(), "enum.max", field.enumMaxItems(), count);
        var positions = new HashMap<String, Integer>();
        int position = 0;
        for (var item : value) {
            position++;
            check(item.isString(), "enum.item.type", position);
            String code = item.asString();
            if (!allowedOptions.contains(code)) {
                throw localized(VALIDATION + "enum.item.option", position, optionExamples(allowedOptions));
            }
            Integer previous = positions.putIfAbsent(code, position);
            check(previous == null, "enum.item.duplicate", position, previous);
        }
    }

    private static DefaultMessageSourceResolvable optionExamples(Set<String> options) {
        if (options.isEmpty()) {
            return detail("enum.noOptions");
        }
        String codes = options.stream().sorted().limit(OPTION_EXAMPLES_LIMIT).collect(Collectors.joining(" / "));
        return detail("enum.examples", codes);
    }

    private static <T extends Comparable<? super T>> void range(T min, T max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw localized("error.field.range");
        }
    }

    private static <T extends Comparable<? super T>> void checkValueRange(T value, T min, T max) {
        if (min != null && value.compareTo(min) < 0) {
            throw localized("error.field.validation.min", boundText(min));
        }
        if (max != null && value.compareTo(max) > 0) {
            throw localized("error.field.validation.max", boundText(max));
        }
    }

    private static String boundText(Object bound) {
        return bound instanceof BigDecimal decimal ? decimal.toPlainString() : bound.toString();
    }
}
