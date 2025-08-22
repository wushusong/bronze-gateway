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
    //后端响应超时时间
    private long backendResponseTimeoutMs = 5000L;
    //客户端写入超时时间
    private long clientWriteTimeoutMs = 5000L;

//    private int maxConnections = 10;
//    private int maxPendingAcquires = 5;
//    private int acquireTimeoutMs = 5000;
    private int connectTimeoutMs = 5000;
    private int maxRetries = -1;
    private int maxContentLength = 1024 * 1024;

    private Resilience resilience = new Resilience();
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

    @Data
    public static class Resilience {
        // 失败率阈值百分比
        private int failureRateThreshold = 50;
        // 慢调用率阈值百分比
        private int slowCallRateThreshold = 50;
        // 慢调用持续时间阈值
        private int slowCallDurationThreshold = 10;
        // 熔断器开启状态持续时间
        private int waitDurationInOpenState = 60;
        // 半开状态允许的调用次数
        private int permittedNumberOfCallsInHalfOpenState = 5;
        // 计算失败率所需的最小调用次数
        private int minimumNumberOfCalls = 10;
        // 滑动窗口大小
        private int slidingWindowSize = 5;
    }

}
