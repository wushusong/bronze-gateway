package com.wss.bronze.gateway.core.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LoadBalancerTypeEnums {

    ROUND_ROBIN("roundRobinLoadBalancer","轮询"),
    WEIGHTED_ROBIN("weightedLoadBalancer","权重"),
    ;

    /**
     * 值
     */
    private final String key;

    /**
     * 描述
     */
    private final String description;

}
