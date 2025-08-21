package com.wss.bronze.gateway.core;

import com.wss.bronze.gateway.core.config.GatewayProperties;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * @author wss
 */
@Data
public class GatewayContext {
    private ChannelHandlerContext ctx;
    private FullHttpRequest request;
    private FullHttpResponse response;
    private GatewayProperties.RouteDefinition route;
    private Map<String, Object> attributes = new HashMap<>();
    private boolean terminated = false;

    public GatewayContext(ChannelHandlerContext ctx, FullHttpRequest request) {
        this.ctx = ctx;
        this.request = request;
    }

    public String getPath() {
        return request.uri();
    }

    public HttpMethod getMethod() {
        return request.method();
    }

    public HttpHeaders getHeaders() {
        return request.headers();
    }

    public void setResponse(FullHttpResponse response) {
        this.response = response;
        ctx.writeAndFlush(response);
        this.terminated = true;
    }

    public void setResponseAndClose(FullHttpResponse response) {
        this.response = response;
        ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        this.terminated = true;
    }
}
