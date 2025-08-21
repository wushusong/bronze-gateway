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

    public void executePreFilters(GatewayContext ctx) {
        try {
            GatewayProperties properties = ApplicationContextHolder.getBean(GatewayProperties.class);

            // 获取所有过滤器定义并按顺序排序
            List<GatewayProperties.FilterDefinition> filterDefinitions = properties.getFilters().stream()
                    .sorted(Comparator.comparingInt(GatewayProperties.FilterDefinition::getOrder))
                    .collect(Collectors.toList());
            if(filterDefinitions.isEmpty()){
                return ;
            }

            // 按顺序执行过滤器
            for (GatewayProperties.FilterDefinition filterDef : filterDefinitions) {
                try {
                    Filter filter = (Filter) ApplicationContextHolder.getBean(filterDef.getName());
                    filter.doFilter(ctx);
                } catch (FilterException e) {
                    log.error("Error executing filter: {}", filterDef.getName(), e);
                    throw e;
                }
            }
        } catch (FilterException e) {
            throw e;
        }catch (Exception e) {
            log.error("Error executing filter chain", e);
            throw new FilterException("Filter chain execution failed");
        }
    }
}
