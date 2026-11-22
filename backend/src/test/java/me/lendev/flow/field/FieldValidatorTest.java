package me.lendev.flow.field;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import java.util.Set;

import me.lendev.flow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.ObjectMapper;

class FieldValidatorTest {

    private final FieldValidator validator = new FieldValidator();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void explainsWhichCharactersAreAllowedInBothLanguages() {
        var config = config("""
                "dataType":"TEXT","textCharLimitEnabled":true,"textAllowChinese":true,"textAllowEnglish":true,
                "textAllowDigits":true,"textAllowSpace":true,"textAllowSeparators":true,
                "textAllowChinesePunctuation":true,"textAllowEnglishPunctuation":true
                """);
        var failure = validationFailure(config, "\"\\uD842\\uDFB7@secret\"", Set.of());
        String chinese = translate(failure, Locale.SIMPLIFIED_CHINESE);
        assertEquals("第 2 个字符「@」属于其他字符。此字段只允许汉字、英文字母（A-Z、a-z）、数字（0-9）、普通空格、连接符（-、_）、"
                + "中文标点（，。！？、；：“”‘’《》〈〉（）【】「」『』…—·）、英文标点（,.!?;:'\"()[]{}）。", chinese);
        String english = translate(failure, Locale.ENGLISH);
        assertTrue(english.contains("Character 2"));
        assertTrue(english.contains("English letters (A-Z, a-z)"));
        assertTrue(english.contains("digits (0-9)"));
        assertFalse(english.contains("secret"));
    }

    @Test
    void identifiesTheRejectedCharacterWithoutEchoingTheWholeValue() {
        var config = config("\"dataType\":\"TEXT\",\"textCharLimitEnabled\":true,\"textAllowChinese\":true");
        var letter = validationFailure(config, "\"aa\"", Set.of());
        assertEquals("第 1 个字符「a」属于英文字母（A-Z、a-z）。此字段只允许汉字。",
                translate(letter, Locale.SIMPLIFIED_CHINESE));
        var newline = validationFailure(config, "\"中\\n\"", Set.of());
        assertTrue(translate(newline, Locale.ENGLISH).contains("a line break"));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "122 aaa|空格|a space",
            "122\\taaa|制表符|a tab",
            "122\\naaa|换行符|a line break",
            "122\\raaa|回车符|a carriage return",
            "122\\u00A0aaa|不换行空格|a non-breaking space",
            "122\\u3000aaa|全角空格|a full-width space"
    }, delimiter = '|')
    void namesInvisibleCharacters(String input, String chineseName, String englishName) {
        var config = config("""
                "dataType":"TEXT","textCharLimitEnabled":true,"textAllowEnglish":true,"textAllowDigits":true
                """);
        var failure = validationFailure(config, "\"" + input + "\"", Set.of());
        assertEquals("第 4 个字符是" + chineseName + "。此字段只允许英文字母（A-Z、a-z）、数字（0-9）。",
                translate(failure, Locale.SIMPLIFIED_CHINESE));
        assertTrue(translate(failure, Locale.ENGLISH).startsWith("Character 4 is " + englishName + "."));
    }

    @Test
    void reportsTheConfiguredPatternAndActualLength() {
        var pattern = validationFailure(config("\"dataType\":\"TEXT\",\"textPattern\":\"[A-Z]{2}[0-9]{3}\""),
                "\"ABC\"", Set.of());
        assertTrue(translate(pattern, Locale.ENGLISH).contains("[A-Z]{2}[0-9]{3}"));
        var length = validationFailure(config("\"dataType\":\"TEXT\",\"textMaxLength\":2"),
                "\"\\uD842\\uDFB7AB\"", Set.of());
        assertEquals("文本最多允许 2 个字符，实际输入了 3 个。", translate(length, Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    void explainsAvailableOptionsAndIdentifiesBothDuplicatePositions() {
        var config = config("\"dataType\":\"ENUM_MULTI\",\"paramGroupId\":1");
        var unknown = validationFailure(config, "[\"A\",\"UNKNOWN\"]", Set.of("Z", "A"));
        String message = translate(unknown, Locale.SIMPLIFIED_CHINESE);
        assertTrue(message.contains("第 2 项"));
        assertTrue(message.contains("A / Z"));
        assertFalse(message.contains("UNKNOWN"));
        var emptyDictionary = validationFailure(config, "[\"A\"]", Set.of());
        assertTrue(translate(emptyDictionary, Locale.ENGLISH).contains("no enabled options"));
        var duplicate = validationFailure(config, "[\"A\",\"Z\",\"Z\"]", Set.of("A", "Z"));
        assertEquals("第 3 项与第 2 项重复，每个选项编码只能选择一次。", translate(duplicate, Locale.SIMPLIFIED_CHINESE));
        var tooMany = validationFailure(config("\"dataType\":\"ENUM_MULTI\",\"paramGroupId\":1,\"enumMaxItems\":1"),
                "[\"A\",\"Z\"]", Set.of("A", "Z"));
        assertEquals("最多允许选择 1 项，实际提交了 2 项。", translate(tooMany, Locale.SIMPLIFIED_CHINESE));
    }

    private BadRequestException validationFailure(FieldModels.Config config, String value, Set<String> options) {
        var input = json.readTree(value);
        return assertThrows(BadRequestException.class, () -> validator.value(config, input, options));
    }

    private static String translate(BadRequestException exception, Locale locale) {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        var message = exception.getApiMessage();
        return messages.getMessage(message.key(), message.arguments(), locale);
    }

    // 用 JSON 转义表示 U+20BB7，验证补充平面汉字按一个字符计数。
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            "dataType":"TEXT","required":true,"defaultValue":"ready" | null | required |
            "dataType":"TEXT","required":true | "  " | required |
            "dataType":"TEXT" | false | type.text |
            "dataType":"TEXT","textMinLength":2 | "" | text.min | 2
            "dataType":"TEXT","textMaxLength":1 | "\\uD842\\uDFB7A" | text.max | 1
            "dataType":"TEXT","textCharLimitEnabled":true,"textAllowChinese":true | "\\uD842\\uDFB7A" | text.characters | 2
            "dataType":"TEXT","textFormat":"EMAIL" | "name@example" | text.email |
            "dataType":"TEXT","textFormat":"URL" | "ftp://example.com" | text.url |
            "dataType":"TEXT","textPattern":"[A-Z]+" | "AB1" | text.pattern |
            "dataType":"INTEGER" | 1 | type.integer |
            "dataType":"INTEGER" | "9223372036854775808" | integer.format |
            "dataType":"INTEGER" | "1.0" | integer.format |
            "dataType":"INTEGER","numberPositiveOnly":true | "0" | number.positive |
            "dataType":"INTEGER","integerMin":"-2" | "-3" | min | -2
            "dataType":"INTEGER","integerMax":"2" | "3" | max | 2
            "dataType":"DECIMAL" | 1.25 | type.decimal |
            "dataType":"DECIMAL" | "1e-2" | decimal.format |
            "dataType":"DECIMAL","numberPositiveOnly":true | "-0.01" | number.positive |
            "dataType":"DECIMAL","decimalScale":2 | "1.230" | decimal.scale | 2
            "dataType":"DECIMAL","decimalPrecision":3 | "12.34" | decimal.precision | 3
            "dataType":"DECIMAL","decimalPrecision":4,"decimalScale":2 | "123" | decimal.integerDigits | 2
            "dataType":"DECIMAL","decimalMin":"0.10" | "0.09" | min | 0.10
            "dataType":"DECIMAL","decimalMax":"0.10" | "0.11" | max | 0.10
            "dataType":"BOOLEAN" | "false" | type.boolean |
            "dataType":"DATE" | 20260101 | type.date |
            "dataType":"DATE" | "2026-02-29" | date.format |
            "dataType":"DATE" | "0999-12-31" | date.year |
            "dataType":"DATE","dateMin":"2026-01-01" | "2025-12-31" | min | 2026-01-01
            "dataType":"DATE","dateMax":"2026-01-01" | "2026-01-02" | max | 2026-01-01
            "dataType":"DATETIME" | {} | type.datetime |
            "dataType":"DATETIME" | "2026-01-01T12:00Z" | datetime.format |
            "dataType":"DATETIME" | "2026-01-01T12:00:00.1234" | datetime.precision |
            "dataType":"DATETIME" | "+10000-01-01T12:00" | date.year |
            "dataType":"DATETIME","dateTimeMin":"2026-01-01T12:00" | "2026-01-01T11:59" | min | 2026-01-01T12:00
            "dataType":"DATETIME","dateTimeMax":"2026-01-01T12:00" | "2026-01-01T12:01" | max | 2026-01-01T12:00
            "dataType":"ENUM","paramGroupId":1 | ["A"] | type.enum |
            "dataType":"ENUM","paramGroupId":1 | "UNKNOWN" | enum.option |
            "dataType":"ATTACHMENT" | "42" | type.attachment |
            "dataType":"ATTACHMENT","required":true | [] | required |
            "dataType":"ATTACHMENT" | [{"documentId":"42","versionId":106}] | attachment.reference |
            "dataType":"ATTACHMENT" | [{"documentId":"9223372036854775808","versionId":"106"}] | attachment.reference |
            "dataType":"ATTACHMENT" | [{"documentId":"42","versionId":"1"},{"documentId":"42","versionId":"2"}] | attachment.duplicate |
            "dataType":"ENUM_MULTI","paramGroupId":1 | "A" | type.enumMulti |
            "dataType":"ENUM_MULTI","paramGroupId":1,"required":true | [] | required |
            "dataType":"ENUM_MULTI","paramGroupId":1,"enumMinItems":1 | [] | enum.min | 1
            "dataType":"ENUM_MULTI","paramGroupId":1,"enumMaxItems":1 | ["A","B"] | enum.max | 1
            "dataType":"ENUM_MULTI","paramGroupId":1 | ["A",null] | enum.item.type | 2
            "dataType":"ENUM_MULTI","paramGroupId":1 | ["A","UNKNOWN"] | enum.item.option | 2
            "dataType":"ENUM_MULTI","paramGroupId":1 | ["A","A"] | enum.item.duplicate | 2
            """)
    void reportsTheFirstViolatedRule(String fields, String input, String code, String argument) {
        var field = config(fields);
        var value = json.readTree(input);
        Set<String> options = Set.of("A", "B");
        var error = assertThrows(BadRequestException.class, () -> validator.value(field, value, options));
        assertEquals("error.field.validation." + code, error.getApiMessage().key());
        if (argument != null) {
            assertEquals(argument, error.getApiMessage().arguments()[0].toString());
        }
    }

    @Test
    void preservesRegexBudgetAndAcceptsOptionalEmptyValues() {
        var field = config("\"dataType\":\"TEXT\",\"textPattern\":\"(a+)+$\"");
        var input = json.valueToTree("a".repeat(2000) + "!");
        Set<String> options = Set.of("A");
        var error = assertThrows(BadRequestException.class, () -> validator.value(field, input, options));
        assertEquals("error.field.pattern.complex", error.getApiMessage().key());
        valid(config("\"dataType\":\"TEXT\""), "\"\"");
        valid(config("\"dataType\":\"ATTACHMENT\""), "[{\"documentId\":\"42\",\"versionId\":\"106\"}]");
        valid(config("\"dataType\":\"ENUM_MULTI\",\"paramGroupId\":1"), "[]");
        valid(config("\"dataType\":\"INTEGER\",\"integerMin\":\"-9223372036854775808\""), "\"-9223372036854775808\"");
        valid(config("\"dataType\":\"DATE\""), "\"2028-02-29\"");
    }

    @Test
    void textCharacterClassesAreAUnionAndCountUnicodeCharacters() {
        var config = config("""
                "dataType":"TEXT","textCharLimitEnabled":true,"textAllowChinese":true,"textAllowDigits":true,
                "textMinLength":1,"textMaxLength":3,"textPattern":"[\\\\p{IsHan}0-9]+"
                """);
        validator.definition(config, Set.of());
        valid(config, "\"\\uD842\\uDFB701\"");
        valid(config, "\"上海\"");
        valid(config, "\"001\"");
        invalid(config, "\"中A\"");
        invalid(config, "\"中０\"");
        invalid(config, "\"\\uD842\\uDFB7012\"");
        invalid(config, "\"中\\n1\"");
    }

    @Test
    void checksSeparatorsPunctuationAndFormat() {
        var config = config("""
                "dataType":"TEXT","textCharLimitEnabled":true,"textAllowEnglish":true,"textAllowSpace":true,
                "textAllowSeparators":true,"textAllowChinesePunctuation":true,"textAllowEnglishPunctuation":true
                """);
        valid(config, "\"ab- _，.\"");
        invalid(config, "\"abc@flow.com\"");
        valid(config("\"dataType\":\"TEXT\",\"textFormat\":\"EMAIL\""), "\"admin@flow.com\"");
        invalid(config("\"dataType\":\"TEXT\",\"textFormat\":\"EMAIL\""), "\"admin@flow\"");
        valid(config("\"dataType\":\"TEXT\",\"textFormat\":\"URL\""), "\"https://flow.test/field\"");
        invalid(config("\"dataType\":\"TEXT\",\"textFormat\":\"URL\""), "\"ftp://flow.test/field\"");
        invalidConfig("\"dataType\":\"TEXT\",\"textPattern\":\"[\"");
        invalidConfig("\"dataType\":\"TEXT\",\"textCharLimitEnabled\":true");
        invalidConfig("\"dataType\":\"TEXT\",\"textAllowChinese\":true");
    }

    @Test
    void validatesPositiveNumbersWithoutRounding() {
        var integer = config("\"dataType\":\"INTEGER\",\"numberPositiveOnly\":true,\"integerMax\":\"9223372036854775807\"");
        validator.definition(integer, Set.of());
        valid(integer, "\"9223372036854775807\"");
        invalid(integer, "\"9223372036854775808\"");
        invalid(integer, "\"0\"");
        invalid(integer, "\"-1\"");
        invalid(integer, "1");
        var decimal = config("""
                "dataType":"DECIMAL","numberPositiveOnly":true,"decimalPrecision":12,"decimalScale":2,
                "decimalMin":"0","decimalMax":"9999999999.99","defaultValue":"0.01"
                """);
        validator.definition(decimal, Set.of());
        valid(decimal, "\"0.01\"");
        valid(decimal, "\"9999999999.99\"");
        invalid(decimal, "\"10000000000.00\"");
        invalid(decimal, "\"0.00\"");
        invalid(decimal, "\"0.001\"");
        invalid(decimal, "\"1e-2\"");
        invalidConfig("\"dataType\":\"INTEGER\",\"numberPositiveOnly\":true,\"integerMax\":\"0\"");
        invalidConfig("\"dataType\":\"DECIMAL\",\"decimalPrecision\":1,\"decimalScale\":2");
        invalidConfig("\"dataType\":\"DECIMAL\",\"decimalMin\":\"2\",\"decimalMax\":\"1\"");
        invalidConfig("\"dataType\":\"DECIMAL\",\"numberPositiveOnly\":true,\"defaultValue\":\"0\"");
    }

    @Test
    void validatesDatesBooleansAndMissingValues() {
        var date = config("\"dataType\":\"DATE\",\"dateMin\":\"2026-01-01\",\"dateMax\":\"2026-12-31\"");
        validator.definition(date, Set.of());
        valid(date, "\"2026-12-31\"");
        invalid(date, "\"2026-02-29\"");
        invalid(date, "\"2025-12-31\"");
        var time = config("\"dataType\":\"DATETIME\",\"dateTimeMax\":\"2026-12-31T10:00:00.123\"");
        validator.definition(time, Set.of());
        valid(time, "\"2026-12-31T10:00:00.123\"");
        invalid(time, "\"2026-12-31T10:00:00.124\"");
        invalid(time, "\"2026-12-31T10:00:00.1229\"");
        var flag = config("\"dataType\":\"BOOLEAN\",\"required\":true,\"defaultValue\":false");
        validator.definition(flag, Set.of());
        valid(flag, "false");
        invalid(flag, "\"false\"");
        invalid(flag, "null");
        valid(date, "null");
        invalidConfig("\"dataType\":\"BOOLEAN\",\"textMaxLength\":10");
        invalidConfig("\"dataType\":\"DATE\",\"dateMin\":\"0999-12-31\"");
        invalidConfig("\"dataType\":\"DATETIME\",\"dateTimeMin\":\"2026-12-31T10:00:00.1234\"");
    }

    @Test
    void validatesDictionaryCodesAndMultiSelectCounts() {
        Set<String> allowed = Set.of("A", "B", "C");
        var single = config("\"dataType\":\"ENUM\",\"paramGroupId\":1,\"defaultValue\":\"A\"");
        validator.definition(single, allowed);
        invalid(single, "\"D\"");
        var multi = config("""
                "dataType":"ENUM_MULTI","paramGroupId":1,"required":true,"enumMinItems":1,"enumMaxItems":2,
                "defaultValue":["A","B"]
                """);
        validator.definition(multi, allowed);
        Set<String> onlyA = Set.of("A");
        assertThrows(BadRequestException.class, () -> validator.definition(multi, onlyA));
        for (String value : new String[]{"[\"A\",\"A\"]", "[\"A\",\"B\",\"C\"]", "[]"}) {
            var input = json.readTree(value);
            assertThrows(BadRequestException.class, () -> validator.value(multi, input, allowed), value);
        }
        invalidConfig("\"dataType\":\"ENUM\"");
        invalidConfig("\"dataType\":\"TEXT\",\"paramGroupId\":1");
    }

    private FieldModels.Config config(String fields) {
        return json.readValue("{\"code\":\"example\",\"name\":\"示例\",\"nameEn\":\"Example\",\"enable\":true,"
                + fields + "}", FieldModels.Config.class);
    }

    private void valid(FieldModels.Config config, String value) {
        assertDoesNotThrow(() -> validator.value(config, json.readTree(value), Set.of()));
    }

    private void invalid(FieldModels.Config config, String value) {
        var input = json.readTree(value);
        Set<String> allowed = Set.of();
        assertThrows(BadRequestException.class, () -> validator.value(config, input, allowed));
    }

    private void invalidConfig(String fields) {
        var definition = config(fields);
        Set<String> allowed = Set.of();
        assertThrows(BadRequestException.class, () -> validator.definition(definition, allowed));
    }
}
