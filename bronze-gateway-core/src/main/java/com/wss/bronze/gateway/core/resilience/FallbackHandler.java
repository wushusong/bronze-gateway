package com.wss.bronze.gateway.core.resilience;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * @author wss
 */
@Component
public class FallbackHandler {

    // 服务特定的降级处理器映射
    private final Map<String, BiConsumer<GatewayContext, String>> fallbackHandlers = new HashMap<>();

    public FallbackHandler() {
        // 注册默认降级处理器
        registerDefaultFallbackHandlers();
    }

    /**
     * 注册默认降级处理器
     */
    private void registerDefaultFallbackHandlers() {
        // 通用服务降级处理器
        fallbackHandlers.put("default", this::handleDefaultFallback);
    }

    /**
     * 处理降级逻辑
     * @param context 网关上下文
     * @param serviceId 服务ID
     * @param reason 降级原因
     */
    public void handleFallback(GatewayContext context, String serviceId, String reason) {
        BiConsumer<GatewayContext, String> handler = fallbackHandlers.getOrDefault(
            serviceId,
            fallbackHandlers.get("default")
        );

        handler.accept(context, reason);
    }

    /**
     * 默认降级处理器
     * @param context 网关上下文
     * @param reason 降级原因
     */
    private void handleDefaultFallback(GatewayContext context, String reason) {
        GwUtils.sendResponse(context.getCtx(), HttpResponseStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable: " + reason,true);
    }


    /**
     * 注册自定义降级处理器
     * @param serviceId 服务ID
     * @param handler 降级处理器
     */
    public void registerFallbackHandler(String serviceId, BiConsumer<GatewayContext, String> handler) {
        fallbackHandlers.put(serviceId, handler);
    }

    /**
     * 移除降级处理器
     * @param serviceId 服务ID
     */
    public void removeFallbackHandler(String serviceId) {
        fallbackHandlers.remove(serviceId);
    }
}
