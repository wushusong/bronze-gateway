package com.wss.bronze.gateway.core.resilience;

import com.wss.bronze.gateway.core.config.GatewayProperties;
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

    public CircuitBreakerManager(GatewayProperties gatewayProperties) {
        // 创建默认的熔断器配置
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                // 失败率阈值百分比
                .failureRateThreshold(gatewayProperties.getResilience().getFailureRateThreshold())
                // 慢调用率阈值百分比
                .slowCallRateThreshold(gatewayProperties.getResilience().getSlowCallRateThreshold())
                // 慢调用持续时间阈值
                .slowCallDurationThreshold(Duration.ofSeconds(gatewayProperties.getResilience().getSlowCallRateThreshold()))
                // 熔断器开启状态持续时间
                .waitDurationInOpenState(Duration.ofSeconds(gatewayProperties.getResilience().getWaitDurationInOpenState()))
                // 半开状态允许的调用次数
                .permittedNumberOfCallsInHalfOpenState(gatewayProperties.getResilience().getPermittedNumberOfCallsInHalfOpenState())
                // 计算失败率所需的最小调用次数
                .minimumNumberOfCalls(gatewayProperties.getResilience().getMinimumNumberOfCalls())
                // 滑动窗口类型
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                // 滑动窗口大小
                .slidingWindowSize(gatewayProperties.getResilience().getSlidingWindowSize())
                // 记录哪些异常
                .recordExceptions(Exception.class)
                // 忽略哪些异常
                .ignoreExceptions()
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
