package com.wss.bronze.gateway.core.router;


import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.config.GatewayProperties;

/**
 * @author wss
 */
public interface Router {
    GatewayProperties.RouteDefinition route(GatewayContext ctx);
}
