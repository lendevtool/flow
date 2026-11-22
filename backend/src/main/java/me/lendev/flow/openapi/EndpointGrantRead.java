package me.lendev.flow.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记经审查的纯读取 Controller。其现有 GET 自动使用接口授权及原 DTO 契约。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EndpointGrantRead {
    String value();
}
