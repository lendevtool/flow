package me.lendev.flow.support;

import java.util.List;
import java.util.UUID;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.util.StringUtils;
import redis.embedded.RedisServer;
import redis.embedded.ports.EphemeralPortProvider;

/**
 * 在同一次测试会话中为 Spring 集成测试和直接使用 Lettuce 的测试共享 Redis。
 */
public final class TestRedis implements ApplicationContextInitializer<ConfigurableApplicationContext>,
        LauncherSessionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestRedis.class);
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String DEFAULT_DATABASE = "15";
    private static final String HOST = "TEST_REDIS_HOST";
    private static final String PORT = "TEST_REDIS_PORT";
    private static final String DATABASE = "TEST_REDIS_DATABASE";
    private static final String USERNAME = "TEST_REDIS_USERNAME";
    private static final String PASSWORD = "TEST_REDIS_PASSWORD";

    private static RedisServer server;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (context.getEnvironment().matchesProfiles("test") && !usesExternalRedis()) {
            TestPropertyValues.of("spring.data.redis.host=" + LOCAL_HOST,
                    "spring.data.redis.port=" + embeddedPort(),
                    "spring.data.redis.database=" + DEFAULT_DATABASE).applyTo(context);
        }
    }

    /**
     * 获取测试 Redis 的连接配置，首次需要时启动内嵌实例。
     *
     * @return Lettuce 可使用的独立测试实例配置
     */
    public static RedisStandaloneConfiguration configuration() {
        int port = usesExternalRedis() ? Integer.parseInt(setting(PORT, "16379")) : embeddedPort();
        var configuration = new RedisStandaloneConfiguration(setting(HOST, LOCAL_HOST), port);
        configuration.setDatabase(Integer.parseInt(setting(DATABASE, DEFAULT_DATABASE)));
        String username = setting(USERNAME, "");
        String password = setting(PASSWORD, "");
        if (StringUtils.hasText(username)) {
            configuration.setUsername(username);
        }
        if (StringUtils.hasText(password)) {
            configuration.setPassword(password);
        }
        return configuration;
    }

    private static boolean usesExternalRedis() {
        return List.of(HOST, PORT, DATABASE, USERNAME, PASSWORD).stream()
                .anyMatch(name -> StringUtils.hasText(setting(name, "")));
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getProperty(name, System.getenv(name));
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private static synchronized int embeddedPort() {
        if (server == null) {
            int port = new EphemeralPortProvider().next();
            var instance = RedisServer.builder().bind(LOCAL_HOST).port(port)
                    .setting("save \"\"")
                    .setting("appendonly no")
                    .setting("dbfilename flow-test-redis-" + UUID.randomUUID() + ".rdb")
                    .build();
            instance.start();
            server = instance;
            LOGGER.info("内嵌测试 Redis 已启动，端口 {}", port);
        }
        return server.ports().getFirst();
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        stopEmbeddedRedis();
    }

    private static synchronized void stopEmbeddedRedis() {
        if (server != null) {
            int port = server.ports().getFirst();
            server.stop();
            server = null;
            LOGGER.info("内嵌测试 Redis 已关闭，端口 {}", port);
        }
    }
}
