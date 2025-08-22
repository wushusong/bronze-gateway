package com.wss.bronze.gateway.core.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * @author wss
 */
@Component
public class ResilienceConfig {


    @DependsOn(value = {"circuitBreakerManager","fallbackHandler"})
    @Bean
    public CircuitBreakerDecorator circuitBreakerDecorator() {
        return new CircuitBreakerDecorator();
    }

    @Bean
    public CircuitBreakerManager circuitBreakerManager() {
        return new CircuitBreakerManager();
    }

    @Bean
    public FallbackHandler fallbackHandler() {
        return new FallbackHandler();
    }

}
