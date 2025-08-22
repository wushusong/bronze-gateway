package com.wss.bronze.gateway.core.loadbalancer;


import com.wss.bronze.gateway.core.config.GatewayProperties;

import java.util.List;

/**
 * @author wss
 */
public interface LoadBalancer {
    GatewayProperties.Instance choose(List<GatewayProperties.Instance> instances,String serviceId);
}
