package com.wss.bronzegateway.core.router;


import com.wss.bronzegateway.config.GatewayProperties;
import com.wss.bronzegateway.core.GatewayContext;

public interface Router {
    GatewayProperties.RouteDefinition route(GatewayContext ctx);
}
