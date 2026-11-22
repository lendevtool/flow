package me.lendev.flow.param.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.param.domain.ParamItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class ParamFieldValidatorTest {

    private static final JsonMapper JSON = new JsonMapper();
    private final ParamFieldValidator validator = new ParamFieldValidator();

    @ParameterizedTest
    @MethodSource("invalidSchemas")
    void rejectsInvalidFieldDefinitions(String input, String error) {
        var schema = JSON.readTree(input);
        assertThatThrownBy(() -> validator.schema(schema)).isInstanceOf(BadRequestException.class).hasMessage(error);
    }

    static Stream<Arguments> invalidSchemas() {
        return Stream.of(
                Arguments.of("[]", "error.param.schema.object"),
                Arguments.of("{\"padding\":\"" + "a".repeat(65_536) + "\"}", "error.param.schema.size"),
                Arguments.of("{\"extraFields\":{}}", "error.param.schema.extra-fields.invalid"),
                Arguments.of("{\"extraFields\":[null]}", "error.param.schema.field.required"),
                Arguments.of("{\"extraFields\":[{\"key\":\"x\",\"label\":\" \"}]}", "error.param.schema.field.label.size"),
                Arguments.of("{\"extraFields\":[{\"key\":\"x\",\"label\":\"X\",\"type\":\"date\"}]}",
                        "error.param.schema.field.type.invalid"),
                Arguments.of("{\"extraFields\":[{\"key\":\"x\",\"label\":\"X\",\"required\":\"true\"}]}",
                        "error.param.schema.field.flag.invalid"),
                Arguments.of("{\"extraFields\":[{\"key\":\"x\",\"label\":\"X\",\"type\":\"select\",\"options\":[]}]}",
                        "error.param.schema.options.size"),
                Arguments.of("""
                        {"extraFields":[{"key":"x","label":"X","type":"select","options":[
                          {"label":"First","value":"a"},{"label":"Second","value":"a"}]}]}
                        """, "error.param.schema.option.invalid"),
                Arguments.of("{\"extraFields\":[{\"key\":\"constructor\",\"label\":\"X\"}]}",
                        "error.param.schema.extra-key.invalid"),
                Arguments.of("{\"fixedFields\":{\"value0\":{\"label\":\"X\"}}}", "error.param.schema.fixed-key.invalid"),
                Arguments.of("{\"fixedFields\":{\"value1\":{\"key\":\"value2\",\"label\":\"X\"}}}",
                        "error.param.schema.fixed-key.immutable"),
                Arguments.of("{\"fixedFields\":{\"value1\":{\"label\":\"X\",\"defaultValue\":\""
                        + "a".repeat(513) + "\"}}}", "error.param.schema.default.size"));
    }

    @Test
    void validatesFixedStringsAndTypedExtraValues() {
        var schema = JSON.readTree("""
                {"fixedFields":{
                  "value1":{"label":"Enabled","type":"boolean"},
                  "value2":{"label":"Amount","type":"number"}
                },"extraFields":[{"key":"quantity","label":"Quantity","type":"number","required":true}]}
                """);
        var item = new ParamItem();
        item.setValue1("true");
        item.setValue2("12.50");
        assertThatThrownBy(() -> validator.values(schema, item)).hasMessage("error.param.field.value.required");
        item.setExtra(JSON.readTree("{\"quantity\":\"2\"}"));
        assertThatThrownBy(() -> validator.values(schema, item)).hasMessage("error.param.field.value.invalid");
        item.setExtra(JSON.readTree("{\"quantity\":2}"));
        item.setValue1("yes");
        assertThatThrownBy(() -> validator.values(schema, item)).hasMessage("error.param.field.value.invalid");
        item.setValue1("false");
        item.setValue2("NaN");
        assertThatThrownBy(() -> validator.values(schema, item)).hasMessage("error.param.field.value.invalid");
        item.setValue2("-12.50");
        assertThatCode(() -> validator.values(schema, item)).doesNotThrowAnyException();

        item.setExtra(JSON.createObjectNode().put("quantity", "a".repeat(65_536)));
        assertThatThrownBy(() -> validator.values(schema, item)).hasMessage("error.param.extra-values.size");
    }

    @Test
    void droppingAnExtraFieldRequiresMigratingExistingDataFirst() {
        var old = JSON.readTree("{\"extraFields\":[{\"key\":\"region\",\"label\":\"Region\"}]}");
        var next = JSON.createObjectNode();
        var item = new ParamItem();
        item.setExtra(JSON.createObjectNode().put("region", "north"));
        var items = List.of(item);
        assertThatThrownBy(() -> validator.change(old, next, items)).hasMessage("error.param.field.has-data");
        item.setExtra(JSON.createObjectNode());
        assertThatCode(() -> validator.change(old, next, items)).doesNotThrowAnyException();
    }
}
