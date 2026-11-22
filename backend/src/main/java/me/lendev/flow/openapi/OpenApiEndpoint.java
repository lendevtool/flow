package me.lendev.flow.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明可登记到开放接口目录的 Controller 方法。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OpenApiEndpoint {
    String value();

    /**
     * 返回早期目录记录使用的契约标记，仅用于来源校验。
     *
     * @return 契约标记
     */
    String dataScopeType() default "MARKET";
}
