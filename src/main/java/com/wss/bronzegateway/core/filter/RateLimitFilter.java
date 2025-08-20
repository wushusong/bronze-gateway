package com.wss.bronzegateway.core.filter;

import com.google.common.util.concurrent.RateLimiter;
import com.wss.bronzegateway.core.GatewayContext;
import com.wss.bronzegateway.utils.GwUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.springframework.stereotype.Component;

@Component("RateLimitFilter") // 指定Bean名称
public class RateLimitFilter implements Filter {

    private final RateLimiter rateLimiter = RateLimiter.create(1); // 1000请求/秒

    @Override
    public boolean doFilter(GatewayContext ctx, Object... args) {
        if (!rateLimiter.tryAcquire()) {
            GwUtils.sendResponse(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, "Too many requests");
            return false;
        }
        return true;
    }

    @Override
    public int getOrder() {
        return -90;
    }

    @Override
    public String getName() {
        return "RateLimitFilter";
    }
}
