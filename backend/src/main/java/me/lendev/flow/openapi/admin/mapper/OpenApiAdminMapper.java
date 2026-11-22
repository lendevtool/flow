package me.lendev.flow.openapi.admin.mapper;

import static me.lendev.flow.openapi.admin.OpenApiAdminModels.*;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 管理配置使用有界分页、显式版本条件和应用行锁。
 */
public interface OpenApiAdminMapper {

    String SCRIPT = "<script>\n";
    String END_SCRIPT = "</script>";
    String PAGE = " LIMIT #{page.limit} OFFSET #{page.offset} ";
    String OWNER_NAME = "COALESCE(NULLIF(u.nameCn, ''), NULLIF(u.nameEn, ''), u.username)";

    String ENDPOINT_COLUMNS = """
            e.id, e.endpoint_code, e.module_code, e.display_name, e.http_method, e.path_template,
            e.api_version, e.data_scope_type, e.status, e.version
            """;
    String READ_MAPPING_COLUMNS = ", m.source_path, m.public_contract_json, m.source_contract_hash ";
    String READ_MAPPING_JOIN = " LEFT JOIN sys_open_api_read_mapping m ON m.endpoint_id = e.id ";
    String GRANTED_COUNT = """
            , (SELECT COUNT(*) FROM sys_open_api_app_grant g JOIN sys_open_api_app a ON a.id = g.app_id
                WHERE g.endpoint_id = e.id AND g.status = 'GRANTED' AND a.environment = #{environment}) AS authorized_count
            """;
    String ENDPOINT_FILTER = """
            <where>
              <if test="moduleCode != null">AND e.module_code = #{moduleCode}</if>
              <if test="status != null">AND e.status = #{status}</if>
              <if test="keyword != null">
                AND (LOWER(e.endpoint_code) LIKE #{keyword} ESCAPE '!'
                  OR LOWER(e.display_name) LIKE #{keyword} ESCAPE '!'
                  OR LOWER(e.path_template) LIKE #{keyword} ESCAPE '!')
              </if>
            </where>
            """;

    @Select(SCRIPT + "SELECT COUNT(*) FROM sys_open_api_endpoint e " + ENDPOINT_FILTER + END_SCRIPT)
    long countEndpoints(@Param("moduleCode") String moduleCode, @Param("status") EndpointStatus status,
                        @Param("keyword") String keyword);

    @Select(SCRIPT + "SELECT " + ENDPOINT_COLUMNS + GRANTED_COUNT + READ_MAPPING_COLUMNS
            + " FROM sys_open_api_endpoint e " + READ_MAPPING_JOIN + ENDPOINT_FILTER + " ORDER BY e.id " + PAGE + END_SCRIPT)
    List<EndpointRow> pageEndpoints(@Param("environment") String environment, @Param("moduleCode") String moduleCode,
                                    @Param("status") EndpointStatus status, @Param("keyword") String keyword,
                                    @Param("page") Pagination page);

    @Select(SCRIPT + "SELECT " + ENDPOINT_COLUMNS + GRANTED_COUNT + READ_MAPPING_COLUMNS + " FROM sys_open_api_endpoint e "
            + READ_MAPPING_JOIN + """
            WHERE e.id = #{id}
            <if test="lock">FOR UPDATE</if>
            """ + END_SCRIPT)
    EndpointRow endpoint(@Param("id") long id, @Param("environment") String environment, @Param("lock") boolean lock);

    @Select("SELECT " + ENDPOINT_COLUMNS + " , 0 AS authorized_count " + READ_MAPPING_COLUMNS
            + " FROM sys_open_api_endpoint e " + READ_MAPPING_JOIN + """
            WHERE e.api_version = 'v1' AND e.status = 'PUBLISHED' ORDER BY e.id
            """)
    List<EndpointRow> publishedEndpointsV1();

    @Insert("""
            INSERT INTO sys_open_api_endpoint
                (endpoint_code, module_code, display_name, http_method, path_template, api_version, data_scope_type,
                 created_at, updated_at, created_by, updated_by)
            VALUES (#{endpointCode}, #{moduleCode}, #{displayName}, 'GET', #{path}, 'v1', #{dataScopeType},
                    #{now}, #{now}, #{actor}, #{actor})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertReadEndpoint(ReadEndpointInsert endpoint);

    @Insert("""
            INSERT INTO sys_open_api_read_mapping (endpoint_id, source_path, public_contract_json, source_contract_hash)
            VALUES (#{id}, #{sourcePath}, #{contract}, #{hash})
            """)
    int insertReadMapping(@Param("id") long id, @Param("sourcePath") String sourcePath,
                          @Param("contract") String contract, @Param("hash") String hash);

    @Update("""
            UPDATE sys_open_api_read_mapping SET source_contract_hash = #{hash} WHERE endpoint_id = #{id}
            """)
    int refreshReadMapping(@Param("id") long id, @Param("hash") String hash);

    @Update("""
            UPDATE sys_open_api_endpoint SET status = #{status}, version = version + 1,
              updated_by = #{actor}, updated_at = #{now}
            WHERE id = #{id} AND version = #{version}
            """)
    int updateEndpointStatus(@Param("id") long id, @Param("version") long version,
                             @Param("status") EndpointStatus status, @Param("actor") int actor,
                             @Param("now") LocalDateTime now);

    String APP_COLUMNS = """
            a.id, a.client_id, a.name, a.environment, a.enabled, a.token_revision, a.owner_user_id,
            a.description, a.rate_limit_count, a.rate_limit_window_seconds, a.version, a.created_at, a.updated_at
            """;
    String APP_OWNER_COLUMN = ", (SELECT " + OWNER_NAME + " FROM `user` u WHERE u.id = a.owner_user_id) AS owner_name ";
    String APP_FILTER = """
            FROM sys_open_api_app a WHERE a.environment = #{environment}
            <if test="selectedEnvironment != null">AND a.environment = #{selectedEnvironment}</if>
            <if test="enabled != null">AND a.enabled = #{enabled}</if>
            <if test="keyword != null">
              AND (LOWER(a.name) LIKE #{keyword} ESCAPE '!'
                OR a.client_id LIKE #{clientKeyword} ESCAPE '!')
            </if>
            """;

    @Select(SCRIPT + "SELECT COUNT(*) " + APP_FILTER + END_SCRIPT)
    long countApps(@Param("environment") String environment, @Param("selectedEnvironment") String selectedEnvironment,
                   @Param("enabled") Boolean enabled, @Param("keyword") String keyword,
                   @Param("clientKeyword") String clientKeyword);

    @Select(SCRIPT + "SELECT " + APP_COLUMNS + APP_OWNER_COLUMN + APP_FILTER + " ORDER BY a.id DESC " + PAGE + END_SCRIPT)
    List<AppRow> pageApps(@Param("environment") String environment,
                          @Param("selectedEnvironment") String selectedEnvironment, @Param("enabled") Boolean enabled,
                          @Param("keyword") String keyword, @Param("clientKeyword") String clientKeyword,
                          @Param("page") Pagination page);

    String ENDPOINT_APPS = """
            FROM sys_open_api_app_grant g JOIN sys_open_api_app a ON a.id = g.app_id
            WHERE g.endpoint_id = #{endpointId} AND g.status = 'GRANTED' AND a.environment = #{environment}
            """;

    @Select("SELECT COUNT(*) " + ENDPOINT_APPS)
    long countEndpointApps(@Param("endpointId") long endpointId, @Param("environment") String environment);

    @Select("SELECT " + APP_COLUMNS + APP_OWNER_COLUMN + ENDPOINT_APPS + " ORDER BY a.id DESC " + PAGE)
    List<AppRow> pageEndpointApps(@Param("endpointId") long endpointId, @Param("environment") String environment,
                                  @Param("page") Pagination page);

    @Select(SCRIPT + "SELECT " + APP_COLUMNS + APP_OWNER_COLUMN + """
            FROM sys_open_api_app a WHERE a.id = #{id} AND a.environment = #{environment}
            <if test="lock">FOR UPDATE</if>
            """ + END_SCRIPT)
    AppRow app(@Param("id") long id, @Param("environment") String environment, @Param("lock") boolean lock);

    @Insert("""
            INSERT INTO sys_open_api_app
              (client_id, name, environment, enabled, token_revision, owner_user_id, description, rate_limit_count,
               rate_limit_window_seconds, version, created_by, updated_by, created_at, updated_at)
            VALUES (#{clientId}, #{config.name}, #{environment}, TRUE, 0, #{config.ownerUserId,jdbcType=INTEGER},
              #{config.description,jdbcType=VARCHAR}, #{config.rateLimitCount}, #{config.rateLimitWindowSeconds},
              0, #{actor}, #{actor}, #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertApp(NewApp app);

    @Update("""
            UPDATE sys_open_api_app SET name = #{config.name}, owner_user_id = #{config.ownerUserId,jdbcType=INTEGER},
              description = #{config.description,jdbcType=VARCHAR}, rate_limit_count = #{config.rateLimitCount},
              rate_limit_window_seconds = #{config.rateLimitWindowSeconds}, version = version + 1,
              updated_by = #{actor}, updated_at = #{now}
            WHERE id = #{id} AND environment = #{environment} AND version = #{version}
            """)
    int updateApp(@Param("id") long id, @Param("environment") String environment, @Param("version") long version,
                  @Param("config") AppConfig config, @Param("actor") int actor, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_open_api_app SET enabled = #{enabled}, token_revision = #{tokenRevision},
              version = version + 1, updated_by = #{actor}, updated_at = #{now}
            WHERE id = #{id} AND environment = #{environment} AND version = #{version}
            """)
    int updateAppStatus(@Param("id") long id, @Param("environment") String environment, @Param("version") long version,
                        @Param("enabled") boolean enabled, @Param("tokenRevision") long tokenRevision,
                        @Param("actor") int actor, @Param("now") LocalDateTime now);

    String OWNER_FILTER = """
            FROM `user` u WHERE u.enable = 1
            <if test="keyword != null">
              AND (LOWER(u.nameCn) LIKE #{keyword} ESCAPE '!'
                OR LOWER(u.nameEn) LIKE #{keyword} ESCAPE '!'
                OR LOWER(u.username) LIKE #{keyword} ESCAPE '!')
            </if>
            """;

    @Select(SCRIPT + "SELECT COUNT(*) " + OWNER_FILTER + END_SCRIPT)
    long countOwners(@Param("keyword") String keyword);

    @Select(SCRIPT + "SELECT u.id, " + OWNER_NAME + " AS display_name " + OWNER_FILTER
            + " ORDER BY display_name, u.id " + PAGE + END_SCRIPT)
    List<OwnerOption> pageOwners(@Param("keyword") String keyword, @Param("page") Pagination page);

    @Select("SELECT u.id FROM `user` u WHERE u.id = #{id} AND u.enable = 1 FOR UPDATE")
    Integer lockEnabledOwner(@Param("id") int id);

    String GRANT_COLUMNS = """
            SELECT g.app_id, g.endpoint_id, e.endpoint_code, e.display_name, e.status AS endpoint_status, e.data_scope_type,
              g.status, g.data_scope_mode, g.data_scope_json, g.rate_limit_count, g.rate_limit_window_seconds, g.version
            FROM sys_open_api_app_grant g JOIN sys_open_api_endpoint e ON e.id = g.endpoint_id
            """;

    @Select(GRANT_COLUMNS + " WHERE g.app_id = #{appId} ORDER BY g.endpoint_id ")
    List<GrantRow> grants(@Param("appId") long appId);

    @Select(GRANT_COLUMNS + " WHERE g.app_id = #{appId} AND g.endpoint_id = #{endpointId} FOR UPDATE ")
    GrantRow lockGrant(@Param("appId") long appId, @Param("endpointId") long endpointId);

    @Insert("""
            INSERT INTO sys_open_api_app_grant (app_id, endpoint_id, status, data_scope_mode, data_scope_json,
              rate_limit_count, rate_limit_window_seconds, version, created_by, updated_by, created_at, updated_at)
            VALUES (#{appId}, #{endpointId}, 'GRANTED', #{config.mode}, #{config.scopeJson,jdbcType=VARCHAR},
              #{config.rateLimitCount,jdbcType=INTEGER}, #{config.rateLimitWindowSeconds,jdbcType=INTEGER},
              0, #{actor}, #{actor}, #{now}, #{now})
            """)
    int insertGrant(@Param("appId") long appId, @Param("endpointId") long endpointId, @Param("config") GrantConfig config,
                    @Param("actor") int actor, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_open_api_app_grant SET status = #{status}, data_scope_mode = #{config.mode},
              data_scope_json = #{config.scopeJson,jdbcType=VARCHAR}, rate_limit_count = #{config.rateLimitCount,jdbcType=INTEGER},
              rate_limit_window_seconds = #{config.rateLimitWindowSeconds,jdbcType=INTEGER}, version = version + 1,
              updated_by = #{actor}, updated_at = #{now}
            WHERE app_id = #{appId} AND endpoint_id = #{endpointId} AND version = #{version}
            """)
    int updateGrant(@Param("appId") long appId, @Param("endpointId") long endpointId, @Param("version") long version,
                    @Param("status") GrantStatus status, @Param("config") GrantConfig config,
                    @Param("actor") int actor, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_open_api_app_grant SET status = 'REVOKED', version = version + 1, updated_by = #{actor}, updated_at = #{now}
            WHERE app_id = #{appId} AND endpoint_id = #{endpointId} AND version = #{version}
            """)
    int revokeGrant(@Param("appId") long appId, @Param("endpointId") long endpointId, @Param("version") long version,
                    @Param("actor") int actor, @Param("now") LocalDateTime now);

    String SECRET_COLUMNS = """
            SELECT version_no, status, expires_at, activated_at, revoked_at, tokens_revoked_at, created_at
            FROM sys_open_api_app_secret WHERE app_id = #{appId}
            """;

    @Select(SECRET_COLUMNS + " ORDER BY version_no DESC ")
    List<SecretRow> secrets(@Param("appId") long appId);

    @Select(SECRET_COLUMNS + " AND status <> 'REVOKED' ORDER BY version_no DESC FOR UPDATE ")
    List<SecretRow> lockRotationSecrets(@Param("appId") long appId);

    @Select(SECRET_COLUMNS + " AND version_no = #{versionNo} FOR UPDATE")
    SecretRow lockSecret(@Param("appId") long appId, @Param("versionNo") int versionNo);

    @Select("""
            SELECT version_no FROM sys_open_api_app_secret WHERE app_id = #{appId}
            ORDER BY version_no DESC LIMIT 1 FOR UPDATE
            """)
    Integer lockLatestSecretVersion(@Param("appId") long appId);

    @Insert("""
            INSERT INTO sys_open_api_app_secret
              (app_id, version_no, secret_hash, status, expires_at, created_by, updated_by, created_at, updated_at)
            VALUES (#{appId}, #{versionNo}, #{hash}, 'CANDIDATE', #{expiresAt}, #{actor}, #{actor}, #{now}, #{now})
            """)
    int insertSecret(@Param("appId") long appId, @Param("versionNo") int versionNo, @Param("hash") String hash,
                     @Param("expiresAt") LocalDateTime expiresAt, @Param("actor") int actor,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_open_api_app_secret SET status = #{next.status}, expires_at = #{next.expiresAt},
              activated_at = #{next.activatedAt,jdbcType=TIMESTAMP}, revoked_at = #{next.revokedAt,jdbcType=TIMESTAMP},
              tokens_revoked_at = #{next.tokensRevokedAt,jdbcType=TIMESTAMP}, updated_by = #{actor}, updated_at = #{now}
            WHERE app_id = #{appId} AND version_no = #{next.versionNo}
            """)
    int updateSecret(@Param("appId") long appId, @Param("next") SecretRow next, @Param("actor") int actor,
                     @Param("now") LocalDateTime now);

    String CALL_FILTER = """
            FROM sys_open_api_call_log WHERE app_id = #{appId}
              AND occurred_at >= #{from} AND occurred_at &lt; #{to}
              <if test="endpointId != null">AND endpoint_id = #{endpointId}</if>
              <if test="requestId != null">AND request_id = #{requestId}</if>
              <if test="statusFloor != null">
                AND http_status >= #{statusFloor} AND http_status &lt; #{statusFloor} + 100
              </if>
            """;

    @Select(SCRIPT + "SELECT COUNT(*) " + CALL_FILTER + END_SCRIPT)
    long countCalls(CallFilter filter);

    @Select(SCRIPT + """
            SELECT id, request_id, occurred_at, duration_ms, http_status, endpoint_id, endpoint_code, error_code, source_ip
            """ + CALL_FILTER + " ORDER BY occurred_at DESC, id DESC " + PAGE + END_SCRIPT)
    List<CallRow> pageCalls(CallFilter filter);

    record Pagination(long offset, int limit) {
    }

    record EndpointRow(long id, String endpointCode, String moduleCode, String displayName, String httpMethod,
                       String pathTemplate, String apiVersion, String dataScopeType, EndpointStatus status,
                       long version, long authorizedCount, String sourcePath, String publicContractJson,
                       String sourceContractHash) {
    }

    @Getter
    @RequiredArgsConstructor
    class ReadEndpointInsert {
        private final String endpointCode;
        private final String moduleCode;
        private final String displayName;
        private final String path;
        private final String dataScopeType;
        private final int actor;
        private final LocalDateTime now;
        @Setter
        private Long id;
    }

    record AppRow(long id, String clientId, String name, String environment, boolean enabled, long tokenRevision,
                  Integer ownerUserId, String description, int rateLimitCount, int rateLimitWindowSeconds,
                  long version, LocalDateTime createdAt, LocalDateTime updatedAt, String ownerDisplayName) {
        public AppConfig config() {
            return new AppConfig(name, ownerUserId, description, rateLimitCount, rateLimitWindowSeconds);
        }
    }

    record AppConfig(String name, Integer ownerUserId, String description, int rateLimitCount,
                     int rateLimitWindowSeconds) {
    }

    @Getter
    @RequiredArgsConstructor
    class NewApp {
        @Setter
        private Long id;
        private final String clientId;
        private final String environment;
        private final AppConfig config;
        private final int actor;
        private final LocalDateTime now;
    }

    record GrantRow(long appId, long endpointId, String endpointCode, String displayName, EndpointStatus endpointStatus,
                    String dataScopeType, GrantStatus status, DataScopeMode mode, String scopeJson,
                    Integer rateLimitCount, Integer rateLimitWindowSeconds, long version) {
        public GrantConfig config() {
            return new GrantConfig(mode, scopeJson, rateLimitCount, rateLimitWindowSeconds);
        }
    }

    record GrantConfig(DataScopeMode mode, String scopeJson, Integer rateLimitCount, Integer rateLimitWindowSeconds) {
    }

    record SecretRow(int versionNo, SecretStatus status, LocalDateTime expiresAt, LocalDateTime activatedAt,
                     LocalDateTime revokedAt, LocalDateTime tokensRevokedAt, LocalDateTime createdAt) {
    }

    record CallFilter(long appId, LocalDateTime from, LocalDateTime to, Long endpointId, String requestId,
                      Integer statusFloor, Pagination page) {
    }

    record CallRow(long id, String requestId, LocalDateTime occurredAt, long durationMs, int httpStatus,
                   Long endpointId,
                   String endpointCode, String errorCode, String sourceIp) {
    }
}
