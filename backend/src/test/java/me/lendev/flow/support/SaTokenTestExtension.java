package me.lendev.flow.support;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpInterface;
import me.lendev.flow.audit.AuditSystemContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 为绕过 Servlet 过滤器链的 Service 层测试建立 Sa-Token 请求上下文， 并打开审计身份作用域，保证测试内可获取登录态与操作人信息。
 */
public class SaTokenTestExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        // Sa-Token 依赖进程级全局单例，多测试上下文并存时会互相覆盖，
        // 因此每个测试前都要把全局 Bean 重新指向当前 Spring 测试上下文。
        var application = SpringExtension.getApplicationContext(context);
        SaManager.setSaTokenDao(application.getBean(SaTokenDao.class));
        SaManager.setStpInterface(application.getBean(StpInterface.class));
        SaManager.setConfig(application.getBean(SaTokenConfig.class));
        SaTokenContextJakartaServletUtil.setContext(
                new MockHttpServletRequest(), new MockHttpServletResponse());
        context.getStore(ExtensionContext.Namespace.create(SaTokenTestExtension.class)).put("auditIdentity",
                AuditSystemContext.open("自动化服务测试"));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        SaTokenContextJakartaServletUtil.clearContext();
        var identity = context.getStore(ExtensionContext.Namespace.create(SaTokenTestExtension.class))
                .remove("auditIdentity", AuditSystemContext.Scope.class);
        if (identity != null) {
            identity.close();
        }
    }
}
