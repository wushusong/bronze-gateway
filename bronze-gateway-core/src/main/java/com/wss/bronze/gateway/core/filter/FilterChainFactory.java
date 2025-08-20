package com.wss.bronze.gateway.core.filter;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.config.ApplicationContextHolder;
import com.wss.bronze.gateway.core.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FilterChainFactory {

    public boolean executePreFilters(GatewayContext ctx) {
        try {
            GatewayProperties properties = ApplicationContextHolder.getBean(GatewayProperties.class);

            // 获取所有过滤器定义并按顺序排序
            List<GatewayProperties.FilterDefinition> filterDefinitions = properties.getFilters().stream()
                    .sorted(Comparator.comparingInt(GatewayProperties.FilterDefinition::getOrder))
                    .collect(Collectors.toList());
            if(filterDefinitions.isEmpty()){
                return true;
            }

            // 按顺序执行过滤器
            for (GatewayProperties.FilterDefinition filterDef : filterDefinitions) {
                try {
                    Filter filter = (Filter) ApplicationContextHolder.getBean(filterDef.getName());
                    boolean b = filter.doFilter(ctx);
                    if (b) {
                        log.debug("Request terminated by filter: {}", filterDef.getName());
                        return b;
                    }
                } catch (Exception e) {
                    log.error("Error executing filter: {}", filterDef.getName(), e);
                    throw new RuntimeException("Filter execution failed: " + filterDef.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error executing filter chain", e);
            throw new RuntimeException("Filter chain execution failed", e);
        }
        return true;
    }
}
