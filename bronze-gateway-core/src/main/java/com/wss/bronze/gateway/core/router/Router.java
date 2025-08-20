package com.wss.bronze.gateway.core.router;


import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.config.GatewayProperties;

public interface Router {
    GatewayProperties.RouteDefinition route(GatewayContext ctx);
}
