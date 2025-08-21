package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * @author wss
 */
@Slf4j
public class HttpClientHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) {
        // 获取网关上下文
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();

        if (gatewayContext != null) {
            try {
                // 复制响应，因为原始响应可能会被释放
                FullHttpResponse response = backendResponse.retainedDuplicate();

                // 将后端服务的响应写回给原始客户端
                gatewayContext.getCtx().writeAndFlush(response).addListener(future -> {
                    if (future.isSuccess()) {
                        log.debug("Successfully sent response to client");
                    } else {
                        log.error("Failed to send response to client", future.cause());
                    }
                    // 关闭与后端服务的连接
                    ctx.close();
                });
            } catch (Exception e) {
                log.error("Error processing backend response", e);
                GwUtils.sendError(gatewayContext, e.getMessage());
            }
        } else {
            log.warn("No gateway context found for backend response");
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Http client error", cause);

        // 获取网关上下文并发送错误响应
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();
        if (gatewayContext != null) {
            GwUtils.sendError(gatewayContext, cause.getMessage());
        }

        ctx.close();
    }


}
