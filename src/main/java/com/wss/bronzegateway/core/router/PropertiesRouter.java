package com.wss.bronzegateway.core.router;

import com.wss.bronzegateway.config.GatewayProperties;
import com.wss.bronzegateway.core.GatewayContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public class PropertiesRouter implements Router {

    @Autowired
    private GatewayProperties properties;

    @Override
    public GatewayProperties.RouteDefinition route(GatewayContext ctx) {
        String path = ctx.getPath();

        return properties.getRoutes().stream()
                .filter(route -> path.startsWith(route.getPath()))
                .min(Comparator.comparingInt(GatewayProperties.RouteDefinition::getOrder))
                .orElse(null);
    }
}
