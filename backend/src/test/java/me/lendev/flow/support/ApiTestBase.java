package me.lendev.flow.support;

import cn.hutool.crypto.digest.DigestUtil;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 接口测试基类，与其它测试共用同一个 Spring 上下文，用例默认回滚
 */
@SpringBootTest
@Import(JdbcFixtureConfiguration.class)
@ExtendWith(SaTokenTestExtension.class)
@ActiveProfiles({"h2", "test"})
@AutoConfigureMockMvc
@Transactional
public abstract class ApiTestBase extends ApiTestSupport {

    @Autowired
    private StringRedisTemplate testRedis;

    @BeforeEach
    void clearFixtureAuthQuotas() {
        String prefix = "flow:auth-rate:{auth-rate}:";
        String local = DigestUtil.sha256Hex("127.0.0.1");
        testRedis.delete(List.of(
                prefix + "login:ip:" + local,
                prefix + "login:ip:" + DigestUtil.sha256Hex(""),
                prefix + "login:account:" + DigestUtil.sha256Hex(ADMIN),
                prefix + "captcha:ip:" + local,
                prefix + "change-password:account:" + DigestUtil.sha256Hex("1")));
    }
}
