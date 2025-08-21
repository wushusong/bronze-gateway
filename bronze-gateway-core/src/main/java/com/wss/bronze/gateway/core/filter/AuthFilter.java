//package com.wss.bronze.gateway.core.filter;
//
//import com.wss.bronze.gateway.core.GatewayContext;
//import com.wss.bronze.gateway.core.utils.GwUtils;
//import io.netty.handler.codec.http.HttpResponseStatus;
//import org.springframework.stereotype.Component;
//import org.springframework.util.StringUtils;
//
//@Component("AuthFilter") // 指定Bean名称
//public class AuthFilter implements Filter {
//
//    @Override
//    public boolean doFilter(GatewayContext ctx, Object... args) {
//        String token = ctx.getHeaders().get("Authorization");
//        if (!StringUtils.hasText(token) || !isValidToken(token)) {
//            GwUtils.sendResponse(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, "Unauthorized");
//            return false;
//        }
//        return true;
//    }
//
//    private boolean isValidToken(String token) {
//        // 简单的token验证逻辑
//        return token != null && token.startsWith("Bearer ");
//    }
//
//    @Override
//    public int getOrder() {
//        return -100;
//    }
//
//    @Override
//    public String getName() {
//        return "AuthFilter";
//    }
//}
