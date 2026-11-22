package me.lendev.flow.common.mybatis;

import com.mybatisflex.core.dialect.DbType;
import com.mybatisflex.core.dialect.DbTypeUtil;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 MyBatis-Flex Mapper 扫描和数据源启动校验。
 */
@Configuration
@MapperScan({"me.lendev.flow.pms.mapper", "me.lendev.flow.param.mapper",
        "me.lendev.flow.calendar.mapper", "me.lendev.flow.field.mapper",
        "me.lendev.flow.workbench.flow.mapper", "me.lendev.flow.workbench.todo.mapper",
        "me.lendev.flow.workbench.series.mapper", "me.lendev.flow.audit.query.mapper",
        "me.lendev.flow.audit.store.mapper", "me.lendev.flow.common.auth.mfa.mapper", "me.lendev.flow.policy.mapper",
        "me.lendev.flow.document.mapper", "me.lendev.flow.docgen.mapper", "me.lendev.flow.institution.mapper", "me.lendev.flow.mail.mapper", "me.lendev.flow.pms.notificationgroup.mapper",
        "me.lendev.flow.pms.inapp.mapper", "me.lendev.flow.pms.workflownotification.mapper", "me.lendev.flow.openapi.mapper"})
public class MybatisFlexConfigure {

    /**
     * 启动时校验数据库配置。
     *
     * <p>在 MyBatis-Flex 缓存 {@code null} 方言之前拒绝未解析的占位符，
     * 让配置缺失以明确的错误信息快速失败，而不是运行期出现难排查的 SQL 异常。</p>
     *
     * @param properties Spring Boot 数据源配置
     * @return MyBatis-Flex 定制器
     */
    @Bean
    public MyBatisFlexCustomizer validateDatabaseConfiguration(DataSourceProperties properties) {
        return config -> {
            String url = properties.getUrl();
            if (missing(url)) {
                throw new IllegalStateException("数据库地址未配置：请设置 backend/loc.env 的 DB_URL 或对应环境变量");
            }
            DbType type = url.startsWith("jdbc:") ? DbTypeUtil.parseDbType(url) : null;
            if (type == null) {
                throw new IllegalStateException("数据库地址无法识别：DB_URL 必须是有效的 jdbc: 连接地址");
            }
            if (type == DbType.MYSQL) {
                if (missing(properties.getUsername())) {
                    throw new IllegalStateException("MySQL 用户名未配置：请设置 DB_USERNAME");
                }
                if (missing(properties.getPassword())) {
                    throw new IllegalStateException("MySQL 密码未配置：请设置 DB_PASSWORD");
                }
            }
        };
    }

    // 值为空或仍包含 "${" 说明环境变量未注入、占位符未被解析，视为缺失
    private static boolean missing(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }
}

