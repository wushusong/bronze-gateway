package com.wss.bronze.gateway.core.router;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于配置属性的路由实现
 * 支持按路径前缀匹配和优先级排序
 *
 * @author wss
 */
@Slf4j
@Component
public class PropertiesRouter implements Router {

    @Autowired
    private GatewayProperties properties;

    private List<GatewayProperties.RouteDefinition> sortedRoutes;

    /**
     * 初始化时对路由进行排序，提高运行时性能
     * 注意，如果后期加了动态刷新yml@RefreshScope 这里需要重新初始化
     */
    @PostConstruct
    public void init() {
        if (properties != null && properties.getRoutes() != null) {
            sortedRoutes = properties.getRoutes();
            log.info("PropertiesRouter initialized with {} routes", sortedRoutes.size());
        } else {
            sortedRoutes = new ArrayList<>();
            log.warn("No routes configured in PropertiesRouter");
        }
    }

    @Override
    public GatewayProperties.RouteDefinition route(GatewayContext ctx) {
        if (ctx == null) {
            log.warn("GatewayContext is null");
            return null;
        }

        String path = ctx.getPath();
        if (path == null || path.isEmpty()) {
            log.warn("Request path is null or empty");
            return null;
        }

        log.debug("Routing request for path: {}", path);

        Optional<GatewayProperties.RouteDefinition> matchedRoute = findMatchingRoute(path);

        if (matchedRoute.isPresent()) {
            GatewayProperties.RouteDefinition route = matchedRoute.get();
            log.debug("Found matching route: {} -> {}", route.getPath(), route.getId());
            return route;
        } else {
            log.debug("No matching route found for path: {}", path);
            return null;
        }
    }

    /**
     * 查找匹配的路由
     *
     * @param path 请求路径
     * @return 匹配的路由定义，如果没找到则返回空
     */
    private Optional<GatewayProperties.RouteDefinition> findMatchingRoute(String path) {
        return sortedRoutes.stream()
                .filter(route -> isPathMatch(path, route.getPath()))
                .findFirst();
    }

    /**
     * 检查路径是否匹配路由前缀
     *
     * @param requestPath 请求路径
     * @param routePath 路由路径前缀
     * @return 是否匹配
     */
    private boolean isPathMatch(String requestPath, String routePath) {
        if (routePath == null || routePath.isEmpty()) {
            return false;
        }

        // 精确匹配
        if (requestPath.equals(routePath)) {
            return true;
        }

        // 前缀匹配（确保路径以/结尾或下一个字符是/）
        if (requestPath.startsWith(routePath)) {
            if (routePath.endsWith("/")) {
                return true;
            }
            // 检查下一个字符是否为/，避免部分路径匹配
            int routePathLength = routePath.length();
            return requestPath.length() > routePathLength &&
                   requestPath.charAt(routePathLength) == '/';
        }

        return false;
    }

    /**
     * 获取当前配置的路由数量
     *
     * @return 路由数量
     */
    public int getRouteCount() {
        return sortedRoutes != null ? sortedRoutes.size() : 0;
    }
}
