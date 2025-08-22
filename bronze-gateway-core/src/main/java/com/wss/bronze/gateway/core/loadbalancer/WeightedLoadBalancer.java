package com.wss.bronze.gateway.core.loadbalancer;

import com.wss.bronze.gateway.core.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权重负载均衡器实现
 * 根据实例的权重值进行负载均衡，权重越高的实例被选中的概率越大
 * 使用加权轮询算法，确保按照权重比例分配请求
 *
 * @author wss
 */
@Component
public class WeightedLoadBalancer implements LoadBalancer {

    /**
     * 存储每个服务的权重轮询状态
     * key: 服务ID, value: 权重轮询器
     */
    private final ConcurrentHashMap<String, WeightedRoundRobin> serviceWeightMap = new ConcurrentHashMap<>();

    @Override
    public GatewayProperties.Instance choose(List<GatewayProperties.Instance> instances) {
        // 参数校验
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        int instanceCount = instances.size();
        if (instanceCount == 1) {
            // 只有一个实例时直接返回
            return instances.get(0);
        }

        // 获取服务ID
        String serviceId = getServiceId(instances);
        if (serviceId == null) {
            return instances.get(0);
        }

        // 获取或创建权重轮询器
        WeightedRoundRobin weightedRoundRobin = serviceWeightMap.computeIfAbsent(serviceId,
            k -> new WeightedRoundRobin(instances));

        // 选择下一个实例
        return weightedRoundRobin.next();
    }

    /**
     * 获取服务ID
     */
    private String getServiceId(List<GatewayProperties.Instance> instances) {
        if (instances != null && !instances.isEmpty()) {
            GatewayProperties.Instance firstInstance = instances.get(0);
            return firstInstance != null ? firstInstance.getServiceId() : null;
        }
        return null;
    }

    /**
     * 清理指定服务的权重轮询状态
     */
    public void removeServiceWeight(String serviceId) {
        if (serviceId != null) {
            serviceWeightMap.remove(serviceId);
        }
    }

    /**
     * 权重轮询器内部类
     * 实现正确的加权轮询算法
     */
    private static class WeightedRoundRobin {
        private final List<GatewayProperties.Instance> instances;
        private final AtomicInteger position = new AtomicInteger(0);

        private int maxWeight = 0;

        private int gcd = 1; // 所有权重的最大公约数

        public WeightedRoundRobin(List<GatewayProperties.Instance> instances) {
            this.instances = instances;
            if(null == instances || instances.isEmpty()){
                maxWeight = 0;
                gcd = 1;
            }else {
                // 计算最大权重
                maxWeight = instances.stream()
                        .mapToInt(GatewayProperties.Instance::getWeight)
                        .max()
                        .orElse(1);

                // 计算所有权重的最大公约数
                gcd = instances.stream()
                        .mapToInt(GatewayProperties.Instance::getWeight)
                        .reduce(this::gcd)
                        .orElse(1);
            }
        }

        private int gcd(int a, int b) {
            if (b == 0) {
                return a;
            }
            return gcd(b, a % b);
        }

        public GatewayProperties.Instance next() {
            if (instances.size() == 1) {
                return instances.get(0);
            }

            int current = position.getAndUpdate(
                    i -> (i + 1) % (maxWeight / gcd)
            );

            for (GatewayProperties.Instance node : instances) {
                if (node.getWeight() >= (current + 1) * gcd) {
                    return node;
                }
            }
            return instances.get(0);
        }
    }
}
