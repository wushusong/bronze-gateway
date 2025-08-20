package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpClientHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) throws Exception {
        // 获取网关上下文
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();

        if (gatewayContext != null) {
            try {
                // 复制响应，因为原始响应可能会被释放
                FullHttpResponse response = backendResponse.copy();

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
                sendError(gatewayContext, e.getMessage());
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
            sendError(gatewayContext, cause.getMessage());
        }

        ctx.close();
    }

    private void sendError(GatewayContext context, String message) {
        io.netty.handler.codec.http.DefaultFullHttpResponse response =
            new io.netty.handler.codec.http.DefaultFullHttpResponse(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR,
                io.netty.buffer.Unpooled.copiedBuffer("Backend error: " + message, io.netty.util.CharsetUtil.UTF_8)
            );
        response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        context.getCtx().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
