package me.lendev.flow.common.auth.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import me.lendev.flow.common.exception.BadRequestException;

/**
 * 域账号使用完整 UPN，并以统一大小写保存与匹配。
 */
public final class DomainAccount {

    private static final Pattern UPN = Pattern.compile("[^\\s\\p{C}@\\\\]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)++",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private DomainAccount() {
    }

    /**
     * 返回用于唯一性约束和登录查找的域账号，格式无效时返回空值。
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.length() <= 254 && UPN.matcher(normalized).matches() ? normalized : null;
    }

    /**
     * 校验管理端输入，空白表示取消绑定。
     */
    public static String validateOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalize(value);
        if (normalized == null) {
            throw BadRequestException.localized("error.user.domain-account.invalid");
        }
        return normalized;
    }
}
