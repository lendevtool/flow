package me.lendev.flow.support;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 让测试夹具的 JDBC 操作与 Mapper 共享 MyBatis-Flex 事务。
 */
@TestConfiguration(proxyBeanMethods = false)
public class JdbcFixtureConfiguration {

    @Bean
    JdbcTemplate jdbcTemplate(SqlSessionFactory sqlSessionFactory) {
        return new JdbcTemplate(sqlSessionFactory.getConfiguration().getEnvironment().getDataSource());
    }
}
