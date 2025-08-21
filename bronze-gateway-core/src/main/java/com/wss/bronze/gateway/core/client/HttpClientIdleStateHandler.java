package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * @author wss
 * 空闲状态检测处理器，用于处理连接超时
 */
@Slf4j
public class HttpClientIdleStateHandler extends IdleStateHandler {

    public HttpClientIdleStateHandler() {
        super(0, 0, 30, TimeUnit.SECONDS); // 30秒读写空闲检测
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (evt.state() == IdleState.ALL_IDLE) {
            // 获取网关上下文
            GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();

            if (gatewayContext != null) {
                log.warn("Connection timeout to backend service");
                sendTimeoutError(gatewayContext);
            }

            // 关闭连接
            ctx.close();
        }
    }

    private void sendTimeoutError(GatewayContext context) {
        io.netty.handler.codec.http.DefaultFullHttpResponse response =
            new io.netty.handler.codec.http.DefaultFullHttpResponse(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpResponseStatus.GATEWAY_TIMEOUT,
                io.netty.buffer.Unpooled.copiedBuffer("Backend service timeout", io.netty.util.CharsetUtil.UTF_8)
            );
        response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        context.getCtx().writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }
}
