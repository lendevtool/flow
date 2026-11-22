package me.lendev.flow.common.auth.bootstrap;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 超级管理员初始化配置项，对应 {@code security.bootstrap} 前缀。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "security.bootstrap")
public class AdminBootstrapProperties {

    private String username = "admin";
    private String password;
}
