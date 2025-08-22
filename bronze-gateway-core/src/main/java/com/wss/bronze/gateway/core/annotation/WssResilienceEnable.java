package com.wss.bronze.gateway.core.annotation;

import com.wss.bronze.gateway.core.resilience.ResilienceConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * @author wss
 */
@Configuration(proxyBeanMethods = false)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
@Documented
@Import(ResilienceConfig.class)
public @interface WssResilienceEnable {
}
