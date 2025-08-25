package com.wss.bronze.gateway.core;

import com.wss.bronze.gateway.core.client.HttpClient;
import com.wss.bronze.gateway.core.config.ApplicationContextHolder;
import com.wss.bronze.gateway.core.config.GatewayProperties;
import com.wss.bronze.gateway.core.enums.LoadBalancerTypeEnums;
import com.wss.bronze.gateway.core.filter.FilterChainFactory;
import com.wss.bronze.gateway.core.filter.FilterException;
import com.wss.bronze.gateway.core.loadbalancer.LoadBalancer;
import com.wss.bronze.gateway.core.loadbalancer.RoundRobinLoadBalancer;
import com.wss.bronze.gateway.core.loadbalancer.WeightedLoadBalancer;
import com.wss.bronze.gateway.core.resilience.CircuitBreakerDecorator;
import com.wss.bronze.gateway.core.router.Router;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 优化的网关服务器处理器
 * 优化点：
 * 1. 提升请求处理性能
 * 2. 改进内存管理和资源释放
 * 3. 优化依赖注入和初始化机制
 * 4. 增强异常处理和监控能力
 *
 * @author wss
 */
@Slf4j
@io.netty.channel.ChannelHandler.Sharable
public class GatewayServerHandler extends ChannelInboundHandlerAdapter {


    // 使用懒加载方式获取依赖
    private volatile Router router;
    private volatile LoadBalancer roundRobinLoadBalancer;
    private volatile LoadBalancer weightedLoadBalancer;
    private volatile FilterChainFactory filterChainFactory;
    private volatile HttpClient httpClient;
    private volatile CircuitBreakerDecorator circuitBreakerDecorator;

    // 依赖初始化状态标记
    private volatile boolean dependenciesInitialized = false;

    // 性能监控计数器
    private final AtomicLong requestCounter = new AtomicLong(0);
    private final AtomicLong errorCounter = new AtomicLong(0);

    // 负载均衡器缓存，避免重复获取
    private final Map<String,LoadBalancer> cachedLoadBalancerMap = new java.util.HashMap<>();

    /**
     * 高性能依赖初始化
     */
    private void ensureDependenciesInitialized() {
        if (dependenciesInitialized) {
            return;
        }

        // 双重检查锁定模式
        if (router == null) {
            synchronized (this) {
                if (router == null) {
                    initializeDependencies();
                    dependenciesInitialized = true;
                }
            }
        }
    }

    /**
     * 依赖初始化
     */
    private void initializeDependencies() {
        try {
            router = ApplicationContextHolder.getBean(Router.class);
            roundRobinLoadBalancer = ApplicationContextHolder.getBean(RoundRobinLoadBalancer.class);
            weightedLoadBalancer = ApplicationContextHolder.getBean(WeightedLoadBalancer.class);
            filterChainFactory = ApplicationContextHolder.getBean(FilterChainFactory.class);
            httpClient = ApplicationContextHolder.getBean(HttpClient.class);

            // 可选依赖
            try {
                circuitBreakerDecorator = ApplicationContextHolder.getBean(CircuitBreakerDecorator.class);
            } catch (Exception ignored) {
                log.debug("CircuitBreakerDecorator not found, circuit breaker disabled");
            }

            log.info("GatewayServerHandler dependencies initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize GatewayServerHandler dependencies", e);
            throw new RuntimeException("Failed to initialize dependencies", e);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 性能计数器
        long requestId = requestCounter.incrementAndGet();

        // 可写状态检查
        if (!ctx.channel().isWritable()) {
            handleChannelNotWritable(ctx, msg, requestId);
            return;
        }

        // 确保依赖已初始化
        ensureDependenciesInitialized();

        if (!(msg instanceof FullHttpRequest)) {
            super.channelRead(ctx, msg);
            return;
        }

        FullHttpRequest fullRequest = (FullHttpRequest) msg;
        GatewayContext context = new GatewayContext(ctx, fullRequest);

        try {
            // 执行过滤器
            try {
                filterChainFactory.executePreFilters(context);
            } catch (FilterException e) {
                handleFilterException(ctx, e, requestId);
                return;
            }

            // 路由选择
            GatewayProperties.RouteDefinition route = router.route(context);
            if (route == null) {
                handleRouteNotFound(ctx, requestId);
                return;
            }

            // 负载均衡选择 + 灰度配置
            GatewayProperties.Instance instance = chooseInstance(context,route);
            if (instance == null) {
                handleNoInstanceAvailable(ctx, requestId);
                return;
            }

            // 转发请求到后端服务
            forwardRequest(context, instance, requestId);

        } catch (Exception e) {
            handleError(ctx, e, requestId);
        } finally {
            // 确保请求对象被释放（在未转发的情况下）
            // 注意：如果请求已转发，HttpClient会负责释放
            // 这里不需要主动释放，避免重复释放
            if (!context.isForwarded()) {
                ReferenceCountUtil.safeRelease(fullRequest);
            }
        }
    }

    /**
     * 基于负载均衡器选择实例
     */
    private GatewayProperties.Instance chooseInstanceByLoadBalancer(GatewayProperties.RouteDefinition route) {
        String loadBalancerType = route.getLoadBalancerType();
        LoadBalancer loadBalancer = getLoadBalancer(loadBalancerType);
        return loadBalancer.choose(route.getInstances(), route.getId());
    }

    /**
     * 判断请求是否符合灰度条件
     */
    private boolean isInGrayGroup(GatewayContext context, GatewayProperties.GrayReleaseConfig grayReleaseConfig) {
        // 1. 基于请求头的灰度策略
        if (grayReleaseConfig.getHeaderBased() != null) {
            GatewayProperties.GrayReleaseConfig.HeaderBased headerBased = grayReleaseConfig.getHeaderBased();
            String headerValue = context.getRequest().headers().get(headerBased.getHeaderName());
            if (headerValue != null && headerBased.getHeaderValues().contains(headerValue)) {
                return true;
            }
        }

        // 2. 基于用户ID的灰度策略
        if (grayReleaseConfig.getUserIdBased() != null) {
            GatewayProperties.GrayReleaseConfig.UserIdBased userIdBased = grayReleaseConfig.getUserIdBased();
            String userId = getUserIdFromContext(context);
            if (userId != null) {
                // 基于用户ID哈希值进行流量分配
                int hash = userId.hashCode();
                int percentage = Math.abs(hash) % 100;
                if (percentage < userIdBased.getPercentage()) {
                    return true;
                }
            }
        }

        // 3. 基于IP的灰度策略
        if (grayReleaseConfig.getIpBased() != null) {
            GatewayProperties.GrayReleaseConfig.IpBased ipBased = grayReleaseConfig.getIpBased();
            String clientIp = getClientIp(context);
            if (clientIp != null && ipBased.getIpRanges().stream().anyMatch(range -> isIpInRange(clientIp, range))) {
                return true;
            }
        }

        // 4. 基于百分比的随机灰度策略
        if (grayReleaseConfig.getPercentageBased() != null) {
            GatewayProperties.GrayReleaseConfig.PercentageBased percentageBased = grayReleaseConfig.getPercentageBased();
            // 使用请求ID或其他唯一标识进行哈希，确保同一请求的一致性
            String requestId = context.getRequest().headers().get("request-id", String.valueOf(System.nanoTime()));
            int hash = requestId.hashCode();
            int percentage = Math.abs(hash) % 100;
            return percentage < percentageBased.getPercentage();
        }

        return false;
    }

    /**
     * 从上下文中获取用户ID
     */
    private String getUserIdFromContext(GatewayContext context) {
        // 尝试从常见头部获取用户ID
        String userId = context.getRequest().headers().get("user-id");
        if (userId != null) {
            return userId;
        }

        userId = context.getRequest().headers().get("X-User-ID");
        if (userId != null) {
            return userId;
        }

        // 可以扩展从token、cookie等获取用户ID的逻辑
        return null;
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(GatewayContext context) {
        String xForwardedFor = context.getRequest().headers().get("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = context.getRequest().headers().get("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        // 从ChannelHandlerContext获取远程地址
        return context.getCtx().channel().remoteAddress().toString().replaceFirst("/", "");
    }

    /**
     * 判断IP是否在指定范围内（简化实现）
     */
    private boolean isIpInRange(String ip, String range) {
        // 简化实现，实际可以支持CIDR格式等
        return ip.startsWith(range) || "*".equals(range) || ip.equals(range);
    }


    /**
     * 基于灰度发布策略选择实例
     */
    private GatewayProperties.Instance chooseInstanceWithGrayRelease(GatewayContext context,
                                                                     GatewayProperties.RouteDefinition route,
                                                                     GatewayProperties.GrayReleaseConfig grayReleaseConfig) {
        try {
            // 判断当前请求是否符合灰度条件
            boolean isInGrayGroup = isInGrayGroup(context, grayReleaseConfig);

            // 根据灰度条件筛选实例
            List<GatewayProperties.Instance> candidateInstances;
            if (isInGrayGroup) {
                // 灰度用户访问灰度实例
                candidateInstances = route.getInstances().stream()
                        .filter(instance -> Boolean.TRUE.equals(instance.getGray()))
                        .collect(java.util.stream.Collectors.toList());

                log.debug("Gray user matched, routing to gray instances. Count: {}", candidateInstances.size());
            } else {
                // 普通用户访问普通实例
                candidateInstances = route.getInstances().stream()
                        .filter(instance -> !Boolean.TRUE.equals(instance.getGray()))
                        .collect(java.util.stream.Collectors.toList());

                log.debug("Normal user, routing to normal instances. Count: {}", candidateInstances.size());
            }

            // 如果筛选后没有可用实例，则回退到全部实例
            if (candidateInstances.isEmpty()) {
                log.warn("No candidate instances found for gray release, fallback to all instances");
                candidateInstances = route.getInstances();
            }

            // 在候选实例中使用负载均衡选择
            String loadBalancerType = route.getLoadBalancerType();
            LoadBalancer loadBalancer = getLoadBalancer(loadBalancerType);
            return loadBalancer.choose(candidateInstances, route.getId());

        } catch (Exception e) {
            log.error("Error in gray release instance selection, fallback to normal selection", e);
            // 出现异常时回退到正常的选择逻辑
            return chooseInstanceByLoadBalancer(route);
        }
    }

    /**
     * 获取负载均衡器
     */
    private LoadBalancer getLoadBalancer(String loadBalancerType) {
        LoadBalancer loadBalancer = cachedLoadBalancerMap.get(loadBalancerType);
        if(null == loadBalancer){
            synchronized (this) {
                if(null == cachedLoadBalancerMap.get(loadBalancerType)){
                    loadBalancer = LoadBalancerTypeEnums.WEIGHTED_ROBIN.getKey().equals(loadBalancerType) ?
                            weightedLoadBalancer : roundRobinLoadBalancer;
                    cachedLoadBalancerMap.put(loadBalancerType, loadBalancer);
                }else {
                    loadBalancer = cachedLoadBalancerMap.get(loadBalancerType);
                }
            }
        }
        return loadBalancer;
    }

    /**
     * 选择服务实例
     */
    private GatewayProperties.Instance chooseInstance(GatewayContext context,GatewayProperties.RouteDefinition route) {
        // 获取灰度发布配置
        GatewayProperties.GrayReleaseConfig grayReleaseConfig = route.getGrayReleaseConfig();

        // 如果没有配置灰度发布，则使用原有逻辑
        if (grayReleaseConfig == null || !grayReleaseConfig.isEnabled()) {
            return chooseInstanceByLoadBalancer(route);
        }

        // 根据灰度策略选择实例
        return chooseInstanceWithGrayRelease(context, route, grayReleaseConfig);
    }

    /**
     * 转发请求到后端服务
     */
    private void forwardRequest(GatewayContext context, GatewayProperties.Instance instance, long requestId) {
        try {
            // 标记请求已转发
            context.setForwarded(true);

            if (circuitBreakerDecorator == null) {
                httpClient.forward(context, instance.getUrl(), false, instance.getServiceId(), null, null);
            } else {
                // 使用熔断器转发请求到后端服务
                circuitBreakerDecorator.executeWithCircuitBreaker(
                        context,
                        instance.getServiceId(),
                        instance.getUrl()
                );
            }

            log.debug("Request {} forwarded to service {} at {}", requestId, instance.getServiceId(), instance.getUrl());
        } catch (Exception e) {
            errorCounter.incrementAndGet();
            log.error("Service request exception for request {}", requestId, e);
            GwUtils.sendResponse(context.getCtx(), HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Service request exception: " + e.getMessage(), true);
        }
    }

    /**
     * 处理通道不可写状态
     */
    private void handleChannelNotWritable(ChannelHandlerContext ctx, Object msg, long requestId) {
        errorCounter.incrementAndGet();
        log.warn("Gateway channel not writable for request {}, dropping request", requestId);
        GwUtils.sendResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Service busy", true);
        ReferenceCountUtil.safeRelease(msg);
    }

    /**
     * 处理过滤器异常
     */
    private void handleFilterException(ChannelHandlerContext ctx, FilterException e, long requestId) {
        errorCounter.incrementAndGet();
        log.debug("Filter exception for request {}: {}", requestId, e.getMessage());
        GwUtils.sendResponse(ctx, e.getStatus(), e.getMessage(), true);
    }

    /**
     * 处理路由未找到
     */
    private void handleRouteNotFound(ChannelHandlerContext ctx, long requestId) {
        errorCounter.incrementAndGet();
        log.debug("No route found for request {}", requestId);
        GwUtils.sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "No route found", true);
    }

    /**
     * 处理无可用实例
     */
    private void handleNoInstanceAvailable(ChannelHandlerContext ctx, long requestId) {
        errorCounter.incrementAndGet();
        log.debug("No available instance for request {}", requestId);
        GwUtils.sendResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "No available instance", true);
    }

    /**
     * 处理一般错误
     */
    private void handleError(ChannelHandlerContext ctx, Exception e, long requestId) {
        errorCounter.incrementAndGet();
        log.error("Gateway process error for request {}", requestId, e);
        GwUtils.sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                "Gateway error: " + e.getMessage(), true);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        errorCounter.incrementAndGet();
        log.error("Gateway channel error", cause);
        GwUtils.sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                "Gateway channel error: " + cause.getMessage(), true);

        // 安全关闭连接
        try {
            ctx.close();
        } catch (Exception e) {
            log.warn("Error closing channel", e);
        }
    }

    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        return String.format("Requests: %d, Errors: %d, Error Rate: %.2f%%",
                requestCounter.get(),
                errorCounter.get(),
                requestCounter.get() > 0 ? (errorCounter.get() * 100.0 / requestCounter.get()) : 0.0);
    }

    /**
     * 重置计数器
     */
    public void resetCounters() {
        requestCounter.set(0);
        errorCounter.set(0);
    }

}
