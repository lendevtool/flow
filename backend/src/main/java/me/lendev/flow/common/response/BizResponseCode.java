package me.lendev.flow.common.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 业务响应码。
 *
 * <p>取值语义：0 成功；400 参数错误；10001-10005 登录与密码类错误；
 * 11003-11006 角色权限类错误；12001-12002 交易日历类错误。
 * 响应体中的 code 取自 {@link #getCode()}，前端按码值分支处理。</p>
 *
 * @author lendev
 */
@Getter
@RequiredArgsConstructor
public enum BizResponseCode {

    /**
     * 正常响应码
     */
    OK(0, "OK"),
    ERR_400(400, "参数错误"),

    ERR_10001(10001, "用户已存在"),
    ERR_10002(10002, "用户名或密码错误"),
    ERR_10003(10003, "验证码错误"),
    ERR_10004(10004, "原密码不正确"),
    PASSWORD_CHANGE_REQUIRED(10005, "请先修改密码"),


    ERR_11003(11003, "无权限，请联系管理员申请权限"),
    ERR_11004(11004, "越权操作"),
    ERR_11005(11005, "您目前暂无此角色或已被禁用，请联系管理员"),
    ERR_11006(11006, "非法操作"),

    TRADING_CALENDAR_COVERAGE_MISSING(12001, "交易日历尚未配置该日期"),
    TRADING_CALENDAR_BASE_CLOSED(12002, "基准日期不是交易日");

    private final int code;

    private final String msg;

}
