package com.wss.bronze.gateway.core.config;

import lombok.Getter;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class ApplicationContextHolder implements ApplicationContextAware {
    @Getter
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        ApplicationContextHolder.applicationContext = applicationContext;
    }

    public static <T> T getBean(Class<T> clazz) {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext is not initialized yet");
        }
        return applicationContext.getBean(clazz);
    }

    public static Object getBean(String name) {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext is not initialized yet");
        }
        return applicationContext.getBean(name);
    }
}
