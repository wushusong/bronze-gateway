package com.wss.bronze.gateway.core;

import com.wss.bronze.gateway.core.client.HttpClient;
import com.wss.bronze.gateway.core.config.ApplicationContextHolder;
import com.wss.bronze.gateway.core.config.GatewayProperties;
import com.wss.bronze.gateway.core.filter.FilterChainFactory;
import com.wss.bronze.gateway.core.loadbalancer.LoadBalancer;
import com.wss.bronze.gateway.core.router.Router;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * @author wss
 */
@Slf4j
@io.netty.channel.ChannelHandler.Sharable
public class GatewayServerHandler extends ChannelInboundHandlerAdapter {

    // 使用懒加载方式获取依赖
    private volatile Router router;
    private LoadBalancer loadBalancer;
    private FilterChainFactory filterChainFactory;
    private HttpClient httpClient;

    // 确保线程安全地初始化依赖
    private final Object lock = new Object();

    private void ensureDependenciesInitialized() {
        if (router == null) {
            synchronized (lock) {
                if (router == null) {
                    router = ApplicationContextHolder.getBean(Router.class);
                    loadBalancer = ApplicationContextHolder.getBean(LoadBalancer.class);
                    filterChainFactory = ApplicationContextHolder.getBean(FilterChainFactory.class);
                    httpClient = ApplicationContextHolder.getBean(HttpClient.class);
                }
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 确保依赖已初始化
        ensureDependenciesInitialized();

        if (!(msg instanceof FullHttpRequest)) {
            super.channelRead(ctx, msg);
            return;
        }

        FullHttpRequest fullRequest = (FullHttpRequest) msg;
        GatewayContext context = new GatewayContext(ctx, fullRequest);

        try {
            // 1. 执行前置过滤器
            boolean filterResult = filterChainFactory.executePreFilters(context);
            if (!filterResult) {
                return;
            }

            // 2. 路由和负载均衡
            GatewayProperties.RouteDefinition route = router.route(context);
            if (route == null) {
                GwUtils.sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "No route found", true);
                return;
            }

            GatewayProperties.Instance instance = loadBalancer.choose(route.getInstances());
            if (instance == null) {
                GwUtils.sendResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "No available instance", true);
                return;
            }

            // 3. 转发请求到后端服务
            httpClient.forward(context, instance.getUrl());

        } catch (Exception e) {
            log.error("Gateway process error", e);
            GwUtils.sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway error: " + e.getMessage(), true);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Gateway channel error", cause);
        GwUtils.sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway channel error: " + cause.getMessage(), true);
    }


}
