package me.lendev.flow.common.auth.domain;

import java.net.URI;
import java.util.Hashtable;
import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.ldap.InitialLdapContext;

import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 使用 Java 默认信任库和主机名校验，与微软 AD DS 建立 LDAPS 用户绑定。
 */
@Component
@Slf4j
public class LdapsDomainAuthenticator implements DomainAuthenticator {

    private final String url;

    public LdapsDomainAuthenticator(@Value("${security.domain.ldaps-url:}") String url) {
        this.url = validUrl(url) ? url : null;
    }

    @Override
    public boolean configured() {
        return url != null;
    }

    @Override
    public boolean authenticate(String account, String password) {
        if (!configured()) {
            throw new DomainUnavailableException();
        }
        if (account == null || password == null || password.isBlank()) {
            return false;
        }
        // InitialLdapContext 的 JNDI 接口要求使用 Hashtable 传递环境配置。
        @SuppressWarnings("java:S1149")
        var settings = new Hashtable<String, String>();
        settings.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        settings.put(Context.PROVIDER_URL, url);
        settings.put(Context.SECURITY_AUTHENTICATION, "simple");
        settings.put(Context.SECURITY_PRINCIPAL, account);
        settings.put(Context.SECURITY_CREDENTIALS, password);
        settings.put(Context.REFERRAL, "throw");
        settings.put("com.sun.jndi.ldap.connect.timeout", "3000");
        settings.put("com.sun.jndi.ldap.read.timeout", "3000");
        try {
            closeAfterBind(new InitialLdapContext(settings, null));
            return true;
        } catch (AuthenticationException rejected) {
            return false;
        } catch (NamingException unavailable) {
            log.warn("企业域认证服务不可用，请检查 LDAPS 连通性、服务端证书及应用信任库，requestId={} errorType={} causeType={}",
                    AuditParticipants.currentRequestId(), unavailable.getClass().getSimpleName(),
                    unavailable.getCause() == null ? null : unavailable.getCause().getClass().getSimpleName());
            throw new DomainUnavailableException();
        }
    }

    private static void closeAfterBind(Context context) {
        try {
            context.close();
        } catch (NamingException ignored) {
            // 绑定已成功。关闭连接异常不影响凭据验证结果。
        }
    }

    private static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "ldaps".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
