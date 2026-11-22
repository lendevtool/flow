package me.lendev.flow.policy;

import java.time.Instant;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * 由模块登记的系统级策略结构和审计字段。
 */
public interface PolicyDefinition {

    /**
     * 返回策略的固定编码，用于登记和持久化定位。
     *
     * @return 全局唯一的策略编码
     */
    String code();

    /**
     * 返回审计和策略管理界面使用的类型名称。
     *
     * @return 策略的显示名称
     */
    String label();

    /**
     * 返回当前部署支持的策略配置结构版本。
     *
     * @return 持久化时所用的结构版本
     */
    int schemaVersion();

    /**
     * 构造可由调用方独立调整的默认配置。
     *
     * @return 该策略类型的完整默认配置
     */
    JsonNode defaults();

    /**
     * 校验完整配置的结构、字段约束及字段之间的依赖。
     *
     * @param config 待校验的策略配置
     */
    void validate(JsonNode config);

    /**
     * 声明策略审计允许展示的字段及其名称。
     *
     * @return 从配置字段路径到审计显示名称的映射
     */
    Map<String, String> fieldLabels();

    /**
     * 在保存前校验配置并返回用于持久化的结构。
     *
     * @param config 调用方提供的完整配置
     * @return 已校验并按策略需要规范化的配置
     */
    default JsonNode normalize(JsonNode config) {
        validate(config);
        return config;
    }

    /**
     * 在策略保存事务中应用该类型依赖的持久化变更。
     */
    default void beforeChange(JsonNode before, JsonNode after, Instant now) {
    }
}
