package com.wss.bronzegatewaydemo.filter;

import com.google.common.util.concurrent.RateLimiter;
import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.filter.Filter;
import com.wss.bronze.gateway.core.filter.FilterException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.springframework.stereotype.Component;

@Component("RateLimitFilter") // 指定Bean名称
public class RateLimitFilter implements Filter {

    private final RateLimiter rateLimiter = RateLimiter.create(1); // 1000请求/秒

    @Override
    public void doFilter(GatewayContext ctx, Object... args) {
        if (!rateLimiter.tryAcquire()) {
            throw new FilterException(HttpResponseStatus.TOO_MANY_REQUESTS, "Too many requests");
        }
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
