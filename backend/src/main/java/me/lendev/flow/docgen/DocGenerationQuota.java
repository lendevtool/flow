package me.lendev.flow.docgen;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 正式生成、试生成与发布样例测试共用每实例并发配额。
 */
@Component
class DocGenerationQuota {
    private final Semaphore permits;

    DocGenerationQuota(@Value("${document.generation.concurrency:2}") int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("文档生成并发配额必须大于零");
        this.permits = new Semaphore(concurrency);
    }

    <T> T run(Supplier<T> action) {
        if (!permits.tryAcquire()) {
            throw new DocGenerationFailure(HttpStatus.TOO_MANY_REQUESTS, "GENERATION_BUSY", "当前生成任务较多，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }
}
