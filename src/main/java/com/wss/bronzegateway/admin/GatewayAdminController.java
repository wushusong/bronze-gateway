package com.wss.bronzegateway.admin;

import com.wss.bronzegateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class GatewayAdminController {

    @Autowired
    private GatewayProperties properties;

    @GetMapping("/routes")
    public List<GatewayProperties.RouteDefinition> getRoutes() {
        return properties.getRoutes();
    }

    @PostMapping("/routes")
    public ResponseEntity<?> addRoute(@RequestBody GatewayProperties.RouteDefinition route) {
        properties.getRoutes().add(route);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<?> deleteRoute(@PathVariable String id) {
        properties.getRoutes().removeIf(r -> r.getId().equals(id));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> result = new HashMap<>();
        result.put("activeConnections", 0);
        result.put("qps", 0);
        result.put("errorRate", 0);
        return result;
    }
}
