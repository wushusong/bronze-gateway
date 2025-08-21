package com.wss.bronze.gateway.core.loadbalancer;

import com.wss.bronze.gateway.core.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, AtomicInteger> positionMap = new ConcurrentHashMap<>();

    @Override
    public GatewayProperties.Instance choose(List<GatewayProperties.Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        //轮询策略
        String key = instances.get(0).getServiceId();
        AtomicInteger position = positionMap.computeIfAbsent(key, k -> new AtomicInteger(0));

        int pos = Math.abs(position.getAndIncrement());
        return instances.get(pos % instances.size());
    }
}
