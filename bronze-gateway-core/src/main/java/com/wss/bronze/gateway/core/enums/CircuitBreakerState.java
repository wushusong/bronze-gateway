package com.wss.bronze.gateway.core.enums;

/**
 * 熔断器状态
 */
public enum CircuitBreakerState {
    // 正常状态，允许请求通过
    CLOSED("关闭"),
    // 熔断状态，拒绝所有请求
    OPEN("打开"),
    // 试探状态，允许部分请求通过
    HALF_OPEN("半开"),
    // 禁用状态，始终允许请求通过
    DISABLED("禁用"),
    // 强制熔断状态
    FORCED_OPEN("强制打开");

    private final String description;

    CircuitBreakerState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static CircuitBreakerState fromResilience4jState(io.github.resilience4j.circuitbreaker.CircuitBreaker.State state) {
        switch (state) {
            case CLOSED: return CLOSED;
            case OPEN: return OPEN;
            case HALF_OPEN: return HALF_OPEN;
            case DISABLED: return DISABLED;
            case FORCED_OPEN: return FORCED_OPEN;
            default: return CLOSED;
        }
    }
}
