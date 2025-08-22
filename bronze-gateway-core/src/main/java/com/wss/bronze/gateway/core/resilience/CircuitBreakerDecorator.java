package com.wss.bronze.gateway.core.resilience;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.client.HttpClient;
import com.wss.bronze.gateway.core.enums.CircuitBreakerState;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * @author wss
 */
@Component
public class CircuitBreakerDecorator {

    @Autowired
    private CircuitBreakerManager circuitBreakerManager;

    @Autowired
    private HttpClient httpClient;

    @Autowired
    private FallbackHandler fallbackHandler;

    /**
     * 使用熔断器包装HTTP调用
     * @param context 网关上下文
     * @param serviceId 服务ID
     * @param url 目标URL
     */
    public void executeWithCircuitBreaker(GatewayContext context, String serviceId, String url) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(serviceId);

        try {
            // 执行HTTP调用，让熔断器自动处理权限检查和状态管理
            Supplier<Void> supplier = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                () -> {
                    try {
                        httpClient.forward(context, url,true,serviceId,circuitBreakerManager,fallbackHandler);
                    } catch (Exception e) {
                        throw new RuntimeException("Invalid URL: " + url, e);
                    }
                    return null;
                }
            );

            supplier.get();
        } catch (CallNotPermittedException e) {
            // 熔断器拒绝调用
            fallbackHandler.handleFallback(context, serviceId, "Call not permitted by circuit breaker");
        } catch (Exception e) {
            // 其他异常，熔断器已经自动记录了失败状态
            fallbackHandler.handleFallback(context, serviceId, "Service call failed: " + e.getMessage());
        }
    }



    /**
     * 获取熔断器状态
     * @param serviceId 服务ID
     * @return 熔断器状态
     */
    public CircuitBreakerState getCircuitBreakerState(String serviceId) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(serviceId);
        return CircuitBreakerState.fromResilience4jState(circuitBreaker.getState());
    }

    /**
     * 获取熔断器指标
     * @param serviceId 服务ID
     * @return 熔断器指标字符串
     */
    public String getCircuitBreakerMetrics(String serviceId) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(serviceId);
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        return String.format(
            "FailureRate: %.2f%%, SlowCallRate: %.2f%%, BufferedCalls: %d, FailedCalls: %d, SlowCalls: %d",
            metrics.getFailureRate(),
            metrics.getSlowCallRate(),
            metrics.getNumberOfBufferedCalls(),
            metrics.getNumberOfFailedCalls(),
            metrics.getNumberOfSlowCalls()
        );
    }
}
