package me.lendev.flow.common.auth.domain;

/**
 * 微软域凭据验证边界，测试可以替换目录连接。
 */
public interface DomainAuthenticator {

    /**
     * 判断部署是否提供了可用的连接配置。
     */
    boolean configured();

    /**
     * 在微软域控制器验证指定 UPN 的密码。
     *
     * @param account  已校验的域账号
     * @param password 本次请求的密码
     * @return 目录接受凭据时返回 true
     * @throws DomainUnavailableException 连接配置或目录服务不可用
     */
    boolean authenticate(String account, String password);
}
