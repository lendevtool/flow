package me.lendev.flow.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;

import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.query.mapper.AuditQueryMapper;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.audit.store.mapper.AuditStoreMapper;
import me.lendev.flow.audit.store.mapper.OperationChangeMapper;
import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class AuditAspectTest {

    private AnnotationConfigApplicationContext app;
    private JdbcTemplate jdbc;
    private Command command;
    static final List<String> stages = new ArrayList<>();

    @BeforeEach
    void setup() {
        app = new AnnotationConfigApplicationContext(Fixture.class);
        jdbc = app.getBean(JdbcTemplate.class);
        new ResourceDatabasePopulator(new ClassPathResource("db/audit-h2.sql")).execute(app.getBean(DataSource.class));
        jdbc.execute("DROP TABLE IF EXISTS audit_demo");
        jdbc.execute("CREATE TABLE audit_demo(id BIGINT PRIMARY KEY, v VARCHAR(100))");
        jdbc.update("INSERT INTO audit_demo VALUES (1,'A')");
        command = app.getBean(Command.class);
        stages.clear();
    }

    @AfterEach
    void close() {
        app.close();
    }

    private String value() {
        return jdbc.queryForObject("SELECT v FROM audit_demo WHERE id=1", String.class);
    }

    @Test
    void runsSnapshotsAndOutboxInsideBusinessTransaction() {
        command.update(1L, "B", false);
        assertThat(stages).containsExactly("before", "business", "after");
        assertEquals("B", value());
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT result FROM sys_audit_outbox", String.class));
        String payload = jdbc.queryForObject("SELECT payload FROM sys_audit_outbox", String.class);
        assertTrue(payload.contains("测试操作者"));
        assertTrue(payload.contains("beforeData"));
    }

    @Test
    void businessFailureRollsBackThenWritesSafeAttempt() {
        assertThrows(IllegalStateException.class, () -> command.update(1L, "B", true));
        assertEquals("A", value());
        assertThat(stages).containsExactly("before", "business");
        assertEquals("FAILURE", jdbc.queryForObject("SELECT result FROM sys_audit_outbox", String.class));
        String payload = jdbc.queryForObject("SELECT payload FROM sys_audit_outbox", String.class);
        assertFalse(payload.contains("secret-error"));
        assertTrue(payload.contains("ATTEMPT"));
    }

    @Test
    void outboxFailureRollsBackBusinessAndCannotBeSwallowed() {
        jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_audit CHECK (id < 0)");
        try {
            assertThrows(RuntimeException.class, () -> command.update(1L, "B", false));
            assertEquals("A", value());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox", Integer.class));
        } finally {
            jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_audit");
        }
    }

    @Test
    void rejectsMissingTransactionReadOnlyAndNestedRoots() {
        assertThrows(IllegalStateException.class, () -> command.noTransaction(1L));
        assertThrows(IllegalStateException.class, () -> command.readOnly(1L));
        var outer = app.getBean(Outer.class);
        assertThrows(IllegalStateException.class, () -> outer.run(1L));
        assertEquals("A", value());
    }

    @Test
    void outerRollbackDoesNotLeaveSuccessfulEvent() {
        var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            command.update(1L, "B", false);
            throw new IllegalStateException("outer rollback");
        }));
        assertEquals("A", value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE result='SUCCESS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE result='FAILURE'", Integer.class));
    }

    @Test
    void noChangeIsNotReportedAsDataMutation() {
        command.update(1L, "A", false);
        assertEquals("NO_CHANGE", jdbc.queryForObject("SELECT result FROM sys_audit_outbox", String.class));
    }

    @Test
    void lostCommitAcknowledgementRecordsUnknownWithoutErasingCommittedFact() {
        app.getBean(AckLossManager.class).loseNextAcknowledgement = true;
        assertThrows(TransactionSystemException.class, () -> command.update(1L, "B", false));
        assertEquals("B", value());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE result='SUCCESS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE result='UNKNOWN'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT operationId) FROM sys_audit_outbox", Integer.class));
    }

    public static class AckLossManager extends DataSourceTransactionManager {

        boolean loseNextAcknowledgement;

        AckLossManager(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (loseNextAcknowledgement) {
                loseNextAcknowledgement = false;
                throw new TransactionSystemException("simulated lost commit acknowledgement");
            }
        }
    }

    public static class Command {

        private final JdbcTemplate jdbc;

        Command(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Audited(AuditOperation.PARAM_GROUP_UPDATE)
        @Transactional(rollbackFor = Exception.class)
        public void update(Long id, String value, boolean fail) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            stages.add("business");
            jdbc.update("UPDATE audit_demo SET v=? WHERE id=?", value, id);
            if (fail) {
                throw new IllegalStateException("secret-error");
            }
        }

        @Audited(AuditOperation.PARAM_GROUP_UPDATE)
        public void noTransaction(Long id) {
            throw new AssertionError("must not execute");
        }

        @Audited(AuditOperation.PARAM_GROUP_UPDATE)
        @Transactional(readOnly = true)
        public void readOnly(Long id) {
            throw new AssertionError("must not execute");
        }
    }

    public static class Outer {

        private final Command command;

        Outer(Command command) {
            this.command = command;
        }

        @Audited(AuditOperation.PARAM_GROUP_UPDATE)
        @Transactional(rollbackFor = Exception.class)
        public void run(Long id) {
            command.update(id, "nested", false);
        }
    }

    @TestConfiguration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({AuditConfiguration.class, AuditRegistry.class, AuditAspect.class, AuditBoundaryAspect.class, AuditExecutor.class,
            AuditRecorder.class, AuditEvents.class})
    static class Fixture {

        @Bean
        DataSource dataSource() {
            var ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:audit_aspect;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            return new FlexDataSource("audit-test", ds);
        }

        @Bean
        JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        AckLossManager transactionManager(DataSource ds) {
            return new AckLossManager(ds);
        }

        @Bean
        ObjectMapper json() {
            return new ObjectMapper();
        }

        @Bean
        AuditProperties properties() {
            return new AuditProperties();
        }

        @Bean
        AuditSnapshots snapshots(ObjectMapper json) {
            return new AuditSnapshots(json);
        }

        @Bean
        SqlSessionFactory sqlSessions(DataSource ds) {
            var configuration = new FlexConfiguration(
                    new Environment("audit-test", new SpringManagedTransactionFactory(), ds));
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            var flex = new FlexGlobalConfig();
            flex.setConfiguration(configuration);
            FlexGlobalConfig.setConfig(configuration.getEnvironment().getId(), flex, false);
            configuration.addMapper(AuditStoreMapper.class);
            configuration.addMapper(AuditQueryMapper.class);
            configuration.addMapper(OperationChangeMapper.class);
            return new SqlSessionFactoryBuilder().build(configuration);
        }

        @Bean
        AuditStore store(SqlSessionFactory sqlSessions, ObjectMapper json, PlatformTransactionManager manager,
                         AuditProperties properties, Clock clock) {
            var session = new SqlSessionTemplate(sqlSessions);
            return new AuditStore(session.getMapper(AuditStoreMapper.class), session.getMapper(AuditQueryMapper.class),
                    sqlSessions, json, manager, properties, clock);
        }

        @Bean
        AuditContext context(Clock clock) {
            return new AuditContext() {
                public AuditScope start(AuditOperation operation, Object[] args) {
                    return AuditScope.builder().operationId(UUID.randomUUID().toString())
                            .requestId(UUID.randomUUID().toString()).startedAt(clock.instant()).startedNanos(System.nanoTime())
                            .actor(AuditActor.system("测试操作者")).sourceType("SYSTEM").targets(List.of()).build();
                }

                public AuditActor currentActor() {
                    return AuditActor.system("测试操作者");
                }
            };
        }

        @Bean
        AuditHandler handler(JdbcTemplate jdbc, AuditSnapshots snapshots) {
            return new AuditHandler() {
                public Set<AuditOperation> operations() {
                    return Set.of(AuditOperation.PARAM_GROUP_UPDATE);
                }

                public Plan prepare(AuditOperation operation, Object[] args) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    stages.add("before");
                    Long id = (Long) args[0];
                    var ref = new AuditObject("PARAM_GROUP", id.toString(), "demo", "示例", null, null);
                    var before = snapshots.capture(ref, Map.of("name", AuditField.visible("名称",
                            jdbc.queryForObject("SELECT v FROM audit_demo WHERE id=? FOR UPDATE", String.class, id))));
                    return result -> {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        stages.add("after");
                        return List.of(snapshots.updated(before, snapshots.capture(ref, Map.of("name", AuditField.visible("名称",
                                jdbc.queryForObject("SELECT v FROM audit_demo WHERE id=? FOR UPDATE", String.class, id))))));
                    };
                }
            };
        }

        @Bean
        Command command(JdbcTemplate jdbc) {
            return new Command(jdbc);
        }

        @Bean
        Outer outer(Command command) {
            return new Outer(command);
        }
    }
}
