package com.wss.bronze.gateway.core.loadbalancer;

import com.wss.bronze.gateway.core.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡器实现
 * 使用原子操作确保线程安全，支持多个服务实例的独立轮询
 *
 * @author wss
 */
@Component
public class RoundRobinLoadBalancer implements LoadBalancer {

    /**
     * 存储每个服务的轮询位置，key为服务ID，value为原子计数器
     */
    private final ConcurrentHashMap<String, AtomicInteger> servicePositionMap = new ConcurrentHashMap<>();

    @Override
    public GatewayProperties.Instance choose(List<GatewayProperties.Instance> instances,String serviceId) {
        // 参数校验
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        int instanceCount = instances.size();
        if (instanceCount == 1) {
            // 只有一个实例时直接返回，避免不必要的计算
            return instances.get(0);
        }

        // 获取服务ID作为轮询的key
        if (serviceId == null) {
            // 如果无法获取服务ID，使用第一个实例
            return instances.get(0);
        }

        // 获取或创建该服务的原子计数器
        AtomicInteger position = servicePositionMap.computeIfAbsent(serviceId, k -> new AtomicInteger(0));

        // 计算下一个位置，使用模运算确保在有效范围内
        // 使用Math.floorMod处理负数情况，确保结果在[0, instanceCount)范围内
        int nextPosition = Math.floorMod(position.getAndIncrement(), instanceCount);

        return instances.get(nextPosition);
    }

    /**
     * 清理指定服务的轮询状态（可选方法，用于服务下线时清理内存）
     *
     * @param serviceId 服务ID
     */
    public void removeServicePosition(String serviceId) {
        if (serviceId != null) {
            servicePositionMap.remove(serviceId);
        }
    }

    /**
     * 获取当前服务的轮询位置（用于监控和调试）
     *
     * @param serviceId 服务ID
     * @return 当前轮询位置，如果服务不存在则返回-1
     */
    public int getCurrentPosition(String serviceId) {
        if (serviceId == null) {
            return -1;
        }
        AtomicInteger position = servicePositionMap.get(serviceId);
        return position != null ? position.get() : -1;
    }
}
