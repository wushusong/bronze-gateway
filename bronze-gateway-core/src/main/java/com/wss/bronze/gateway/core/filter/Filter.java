package com.wss.bronze.gateway.core.filter;


import com.wss.bronze.gateway.core.GatewayContext;

public interface Filter {
    boolean doFilter(GatewayContext ctx, Object... args) throws Exception;
    int getOrder();
    String getName();
}
