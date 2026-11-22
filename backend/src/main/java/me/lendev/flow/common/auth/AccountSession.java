package me.lendev.flow.common.auth;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;

import java.util.Objects;

import me.lendev.flow.pms.domain.entity.User;

/**
 * 将登录令牌绑定到账号实例（identityKey），而非可复用的数据库自增 ID， 防止账号被删除重建后旧令牌继续有效。
 */
public final class AccountSession {

    public static final String IDENTITY_KEY = "accountIdentity";

    private AccountSession() {
    }

    /**
     * 判断当前令牌会话是否与给定用户属于同一账号实例
     *
     * @param user 待校验的用户，可为 {@code null}
     * @return 会话中的 identityKey 与用户一致时返回 {@code true}
     */
    public static boolean matches(User user) {
        var session = StpUtil.getStpLogic().getTokenSession(false);
        return user != null && user.getIdentityKey() != null && session != null
                && Objects.equals(user.getIdentityKey(), session.getString(IDENTITY_KEY));
    }

    /**
     * 校验令牌与账号实例的绑定关系，不一致时视为登录失效
     *
     * @param user 当前登录用户
     */
    public static void check(User user) {
        if (!matches(user)) {
            throw NotLoginException.newInstance(StpUtil.getLoginType(), NotLoginException.INVALID_TOKEN,
                    "登录凭证已失效，请重新登录", StpUtil.getTokenValue());
        }
    }
}
