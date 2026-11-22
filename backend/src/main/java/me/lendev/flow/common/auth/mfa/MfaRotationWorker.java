package me.lendev.flow.common.auth.mfa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditSystemContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每次处理有限批次。多实例通过密文条件更新避免覆盖较新的绑定。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MfaRotationWorker {

    private final MfaRotationService service;
    private final MfaRotationStore store;
    private final MfaSecrets secrets;

    @Value("${security.mfa.rotation-worker-enabled:true}")
    private boolean enabled;

    /**
     * 周期性推进轮转。当前周期遇到存储异常时，由后续周期继续处理。
     */
    @Scheduled(fixedDelayString = "${security.mfa.rotation-poll-ms:30000}")
    void scheduledTick() {
        if (!enabled) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException unavailable) {
            log.warn("MFA 密钥轮转本轮中断，后续周期将重试，请检查轮转状态及数据库连接，errorType={}",
                    unavailable.getClass().getSimpleName());
        }
    }

    /**
     * 推进到期轮转及一批存量绑定。
     */
    public void tick() {
        try (var ignored = AuditSystemContext.open("MFA 密钥轮转任务")) {
            var state = service.status();
            if (state.overdue() && state.canStart() && !"FAILED".equals(state.status())) {
                service.start(true);
                state = service.status();
            }
            if (!"RUNNING".equals(state.status())) {
                return;
            }
            String active = state.activeKeyId();
            if (!secrets.hasKey(active)) {
                log.warn("MFA 轮转缺少当前写入版本的密钥，本实例暂停处理，请同步密钥配置并刷新后确认轮转推进，keyVersion={} rotationStatus={}",
                        active, state.status());
                return;
            }
            try {
                for (var binding : store.batch(active)) {
                    byte[] seed = secrets.open(binding.userId(), binding.ciphertext());
                    store.rewrite(binding, active, secrets.protect(binding.userId(), active, seed));
                }
            } catch (MfaUnavailableException unavailable) {
                log.warn("MFA 存量绑定轮转换密失败，正在登记轮转失败，请核对新旧密钥版本是否齐备及存量绑定能否解封，keyVersion={} errorType={}",
                        active, unavailable.getClass().getSimpleName());
                service.fail(active);
                return;
            }
            if (store.pendingCount(active) == 0) {
                service.complete(active);
            }
        }
    }
}
