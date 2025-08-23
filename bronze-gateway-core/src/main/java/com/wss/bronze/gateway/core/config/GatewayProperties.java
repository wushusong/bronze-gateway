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
    //连接超时时间
    private int connectTimeoutMs = 5000;
    //每个主机的最大并发连接数（如果没有配置，使用合理默认值）
    private int maxConnectionsPerHost = 500;
    //每个主机最大等待连接数（如果没有配置，使用合理默认值）
    private int maxPendingAcquires = 20000;
    //重试次数 -1不重试
    private int maxRetries = -1;
    //报文最大长度
    private int maxContentLength = 1024 * 1024;

    //最大接收缓冲区
    private int soRcvbuf = 128 * 1024;
    //最大发送缓冲区
    private int soSndbuf = 128 * 1024;

    //cpu核数
    private int cpuMaxThreadCount = 64;

    private Resilience resilience = new Resilience();
    private List<RouteDefinition> routes = new ArrayList<>();
    private List<FilterDefinition> filters = new ArrayList<>();

    @Data
    public static class RouteDefinition {
        //服务id，每个服务唯一
        private String id;
        //服务访问路径，注意，前后都要斜杠，不能有*号
        private String path;
        //负载均衡类型 roundRobinLoadBalancer轮询 / WeightedLoadBalancer权重
        private String loadBalancerType = LoadBalancerTypeEnums.ROUND_ROBIN.getKey();
        //过滤器
        private List<FilterDefinition> filters = new ArrayList<>();
        //服务实例
        private List<Instance> instances = new ArrayList<>();
    }

    @Data
    public static class FilterDefinition {
        //路由名称，要求与@Component("AuthFilter")匹配
        private String name;
        private Map<String, String> args = new HashMap<>();
        private int order = 0;
    }

    @Data
    public static class Instance {
        //同一个服务，不同实例id，要求唯一
        private String serviceId;
        //实例地址，http://localhost:8081 # 服务地址，到端口一层即可
        private String url;
        //权重，目前系统默认轮询策略
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
