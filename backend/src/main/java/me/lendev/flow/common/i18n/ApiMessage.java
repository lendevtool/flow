package me.lendev.flow.common.i18n;

import java.util.Arrays;
import java.util.Objects;

/**
 * 交给 HTTP 层翻译的消息键及其插值参数。
 */
public record ApiMessage(String key, Object... arguments) {

    @Override
    public boolean equals(Object other) {
        return other instanceof ApiMessage(var otherKey, var otherArguments)
                && Objects.equals(key, otherKey)
                && Arrays.equals(arguments, otherArguments);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(key) + Arrays.hashCode(arguments);
    }

    @Override
    public String toString() {
        return "ApiMessage[key=" + key + ", arguments=" + Arrays.toString(arguments) + "]";
    }
}
