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
    private LoadBalancer roundRobinLoadBalancer;
    private LoadBalancer weightedLoadBalancer;
    private FilterChainFactory filterChainFactory;
    private HttpClient httpClient;

    // 确保线程安全地初始化依赖
    private final Object lock = new Object();

    private void ensureDependenciesInitialized() {
        if (router == null) {
            synchronized (lock) {
                if (router == null) {
                    router = ApplicationContextHolder.getBean(Router.class);
                    roundRobinLoadBalancer = ApplicationContextHolder.getBean(RoundRobinLoadBalancer.class);
                    weightedLoadBalancer = ApplicationContextHolder.getBean(WeightedLoadBalancer.class);
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

        boolean forwarded = false;
        try {
            //执行过滤器
            try {
                filterChainFactory.executePreFilters(context);
            }catch (FilterException e){
                GwUtils.sendResponse(ctx, e.getStatus(), e.getMessage(), true);
                return;
            }

            //路由选择
            GatewayProperties.RouteDefinition route = router.route(context);
            if (route == null) {
                GwUtils.sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "No route found", true);
                return;
            }

            // 负载均衡选择
            GatewayProperties.Instance instance = null;
            if(LoadBalancerTypeEnums.WEIGHTED_ROBIN.getKey().equals(route.getLoadBalancerType())){
                instance = weightedLoadBalancer.choose(route.getInstances());
            }else {
                instance = roundRobinLoadBalancer.choose(route.getInstances());
            }

            if (instance == null) {
                GwUtils.sendResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "No available instance", true);
                return;
            }

            //转发请求到后端服务
            httpClient.forward(context, instance.getUrl());
            forwarded = true;
        } catch (Exception e) {
            log.error("Gateway process error", e);
            GwUtils.sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway error: " + e.getMessage(), true);
        } finally {
            if (!forwarded) {
                io.netty.util.ReferenceCountUtil.release(fullRequest);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Gateway channel error", cause);
        GwUtils.sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway channel error: " + cause.getMessage(), true);
    }


}
