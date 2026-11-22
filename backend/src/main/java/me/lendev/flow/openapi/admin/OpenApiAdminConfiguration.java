package me.lendev.flow.openapi.admin;

import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 注册开放 API 管理配置的 MyBatis-Flex Mapper。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = OpenApiAdminMapper.class)
class OpenApiAdminConfiguration {
}
