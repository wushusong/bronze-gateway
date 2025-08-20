package com.wss.bronzegateway.core.filter;

import com.wss.bronzegateway.core.GatewayContext;

public interface Filter {
    boolean doFilter(GatewayContext ctx, Object... args) throws Exception;
    int getOrder();
    String getName();
}
