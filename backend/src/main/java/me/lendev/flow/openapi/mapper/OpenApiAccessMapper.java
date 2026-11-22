package me.lendev.flow.openapi.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 开放 API 运行时读取已提交的配置，并批量维护调用记录。
 */
public interface OpenApiAccessMapper {
    @Select("""
            SELECT id, client_id AS clientId, enabled, token_revision AS tokenRevision FROM sys_open_api_app
            WHERE client_id = #{clientId} AND environment = #{environment}
            """)
    Client client(@Param("clientId") String clientId, @Param("environment") String environment);

    @Select("""
            SELECT id, secret_hash AS hash FROM sys_open_api_app_secret
            WHERE app_id = #{appId} AND status IN ('CANDIDATE', 'ACTIVE', 'RETIRING')
              AND expires_at > #{now} AND tokens_revoked_at IS NULL
            ORDER BY version_no DESC LIMIT 3
            """)
    List<Secret> signingSecrets(@Param("appId") long appId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT e.endpoint_code AS endpointCode, e.http_method AS httpMethod, e.path_template AS pathTemplate,
                   e.api_version AS apiVersion, e.data_scope_type AS dataScopeType, m.source_path AS sourcePath,
                   m.source_contract_hash AS sourceContractHash, m.public_contract_json AS publicContractJson
            FROM sys_open_api_app_grant g JOIN sys_open_api_endpoint e ON e.id = g.endpoint_id
            LEFT JOIN sys_open_api_read_mapping m ON m.endpoint_id = e.id
            WHERE g.app_id = #{appId} AND g.status = 'GRANTED' AND e.status = 'PUBLISHED'
            ORDER BY e.endpoint_code
            """)
    List<PublishedScope> publishedScopes(@Param("appId") long appId);

    String ACCESS_SELECT = """
            SELECT a.enabled, a.token_revision AS tokenRevision, a.rate_limit_count AS appLimit,
                   a.rate_limit_window_seconds AS appWindow, s.status AS secretStatus,
                   s.expires_at AS secretExpiresAt, s.tokens_revoked_at AS tokensRevokedAt,
                   e.id AS endpointId, e.status AS endpointStatus, e.http_method AS httpMethod,
                   e.path_template AS pathTemplate, e.api_version AS apiVersion, e.data_scope_type AS dataScopeType,
                   g.status AS grantStatus,
                   g.rate_limit_count AS endpointLimit, g.rate_limit_window_seconds AS endpointWindow,
                   e.endpoint_code AS endpointCode, m.source_path AS sourcePath,
                   m.source_contract_hash AS sourceContractHash, m.public_contract_json AS publicContractJson
            FROM sys_open_api_app a
            LEFT JOIN sys_open_api_app_secret s ON s.id = #{secretId} AND s.app_id = a.id
            """;

    String ACCESS_JOINS = """
            LEFT JOIN sys_open_api_read_mapping m ON m.endpoint_id = e.id
            LEFT JOIN sys_open_api_app_grant g ON g.app_id = a.id AND g.endpoint_id = e.id
            WHERE a.id = #{appId} AND a.client_id = #{clientId} AND a.environment = #{environment}
            """;

    @Select(ACCESS_SELECT + " LEFT JOIN sys_open_api_endpoint e ON e.endpoint_code = #{endpointCode} " + ACCESS_JOINS)
    Access access(@Param("appId") long appId, @Param("clientId") String clientId, @Param("secretId") long secretId,
                  @Param("endpointCode") String endpointCode, @Param("environment") String environment);

    @Select(ACCESS_SELECT + " LEFT JOIN sys_open_api_endpoint e ON e.http_method = 'GET' AND e.path_template = #{path} "
            + ACCESS_JOINS)
    Access managedAccess(@Param("appId") long appId, @Param("clientId") String clientId, @Param("secretId") long secretId,
                         @Param("path") String path, @Param("environment") String environment);

    @Insert("""
            <script>
            INSERT INTO sys_open_api_call_log (request_id, app_id, client_id, endpoint_id, endpoint_code,
                occurred_at, duration_ms, http_status, error_code, source_ip)
            VALUES <foreach collection='calls' item='row' separator=','>
               (#{row.requestId}, #{row.appId}, #{row.clientId}, #{row.endpointId}, #{row.endpointCode},
                #{row.occurredAt}, #{row.durationMs}, #{row.httpStatus}, #{row.errorCode}, #{row.sourceIp})
            </foreach>
            </script>
            """)
    int insertCalls(@Param("calls") List<Call> calls);

    @Select("""
            SELECT id FROM sys_open_api_call_log WHERE occurred_at < #{cutoff}
            ORDER BY occurred_at, id LIMIT #{limit}
            """)
    List<Long> expiredCalls(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM sys_open_api_call_log WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    int deleteCalls(@Param("ids") List<Long> ids);

    record Client(long id, String clientId, boolean enabled, long tokenRevision) {
    }

    record Secret(long id, String hash) {
    }

    record PublishedScope(String endpointCode, String httpMethod, String pathTemplate, String apiVersion,
                          String dataScopeType, String sourcePath, String sourceContractHash,
                          String publicContractJson) {
    }

    record Access(boolean enabled, long tokenRevision, int appLimit, int appWindow, String secretStatus,
                  LocalDateTime secretExpiresAt, LocalDateTime tokensRevokedAt, Long endpointId, String endpointStatus,
                  String httpMethod, String pathTemplate, String apiVersion, String dataScopeType, String grantStatus,
                  Integer endpointLimit, Integer endpointWindow,
                  String endpointCode, String sourcePath, String sourceContractHash, String publicContractJson) {
    }

    record Call(String requestId, long appId, String clientId, Long endpointId, String endpointCode,
                LocalDateTime occurredAt, long durationMs, int httpStatus, String errorCode, String sourceIp) {
    }
}
