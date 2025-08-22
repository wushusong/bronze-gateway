package com.wss.bronze.gateway.core.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author wss
 */
@Component
public class CircuitBreakerManager {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public CircuitBreakerManager() {
        // 创建默认的熔断器配置
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // 失败率阈值百分比
                .slowCallRateThreshold(50) // 慢调用率阈值百分比
                .slowCallDurationThreshold(Duration.ofSeconds(1)) // 慢调用持续时间阈值
                .waitDurationInOpenState(Duration.ofSeconds(60)) // 熔断器开启状态持续时间
                .permittedNumberOfCallsInHalfOpenState(5) // 半开状态允许的调用次数
                .minimumNumberOfCalls(10) // 计算失败率所需的最小调用次数
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED) // 滑动窗口类型
                .slidingWindowSize(5) // 滑动窗口大小
                .recordExceptions(Exception.class) // 记录哪些异常
                .ignoreExceptions() // 忽略哪些异常
                .build();

        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    /**
     * 获取或创建熔断器
     * @param serviceId 服务ID
     * @return 熔断器实例
     */
    public CircuitBreaker getCircuitBreaker(String serviceId) {
        return circuitBreakers.computeIfAbsent(serviceId, circuitBreakerRegistry::circuitBreaker);
    }

    /**
     * 获取所有熔断器
     * @return 熔断器映射
     */
    public Map<String, CircuitBreaker> getAllCircuitBreakers() {
        return new ConcurrentHashMap<>(circuitBreakers);
    }

    /**
     * 移除熔断器
     * @param serviceId 服务ID
     */
    public void removeCircuitBreaker(String serviceId) {
        circuitBreakers.remove(serviceId);
    }

    /**
     * 重置熔断器
     * @param serviceId 服务ID
     */
    public void resetCircuitBreaker(String serviceId) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(serviceId);
        if (circuitBreaker != null) {
            circuitBreaker.reset();
        }
    }
}
