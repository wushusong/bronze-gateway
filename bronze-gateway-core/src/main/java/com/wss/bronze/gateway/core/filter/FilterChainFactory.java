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
            GatewayProperties properties = getGatewayProperties();
            if (properties == null || properties.getFilters() == null || properties.getFilters().isEmpty()) {
                log.debug("No filters configured, skipping filter execution");
                return;
            }

            List<GatewayProperties.FilterDefinition> filterDefinitions = sortFilterDefinitions(properties);
            log.debug("Executing {} pre-filters in order", filterDefinitions.size());

            executeFilters(ctx, filterDefinitions);
            log.debug("Pre-filter execution completed successfully");

        } catch (FilterException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during filter chain execution", e);
            throw new FilterException("Filter chain execution failed: " + e.getMessage());
        }
    }

    private GatewayProperties getGatewayProperties() {
        try {
            return ApplicationContextHolder.getBean(GatewayProperties.class);
        } catch (Exception e) {
            log.error("Failed to retrieve GatewayProperties bean", e);
            throw new FilterException("Gateway configuration unavailable");
        }
    }

    private List<GatewayProperties.FilterDefinition> sortFilterDefinitions(GatewayProperties properties) {
        return properties.getFilters().stream()
                .sorted(Comparator.comparingInt(GatewayProperties.FilterDefinition::getOrder))
                .collect(Collectors.toList());
    }

    private void executeFilters(GatewayContext ctx, List<GatewayProperties.FilterDefinition> filterDefinitions) {
        for (GatewayProperties.FilterDefinition filterDef : filterDefinitions) {
            try {
                Filter filter = (Filter) ApplicationContextHolder.getBean(filterDef.getName());
                if (filter == null) {
                    log.warn("Filter bean '{}' not found, skipping", filterDef.getName());
                    continue;
                }
                log.debug("Executing filter: {}", filterDef.getName());
                filter.doFilter(ctx);
            } catch (FilterException e) {
                log.error("Filter '{}' threw FilterException", filterDef.getName(), e);
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error in filter '{}'", filterDef.getName(), e);
                throw new FilterException("Filter '" + filterDef.getName() + "' execution failed: " + e.getMessage());
            }
        }
    }
}
