package com.wss.bronzegateway.core.loadbalancer;


import com.wss.bronzegateway.config.GatewayProperties;

import java.util.List;

public interface LoadBalancer {
    GatewayProperties.Instance choose(List<GatewayProperties.Instance> instances);
}
