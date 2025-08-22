package com.wss.bronze.gateway.core.config;

import com.wss.bronze.gateway.core.enums.LoadBalancerTypeEnums;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author wss
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway")
//@RefreshScope
public class GatewayProperties {

    private int port = 9999;
    private long backendResponseTimeoutMs = 5000L;
    private long clientWriteTimeoutMs = 5000L;

    private List<RouteDefinition> routes = new ArrayList<>();
    private List<FilterDefinition> filters = new ArrayList<>();

    @Data
    public static class RouteDefinition {
        private String id;
        private String path;
        private String loadBalancerType = LoadBalancerTypeEnums.ROUND_ROBIN.getKey();
        private List<FilterDefinition> filters = new ArrayList<>();
        private List<Instance> instances = new ArrayList<>();
    }

    @Data
    public static class FilterDefinition {
        private String name;
        private Map<String, String> args = new HashMap<>();
        private int order = 0;
    }

    @Data
    public static class Instance {
        private String serviceId;
        private String url;
        private int weight = 1;
        private boolean healthy = true;
    }

}
