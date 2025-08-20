package com.wss.bronze.gateway.core.utils;

import com.wss.bronze.gateway.core.GatewayContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

public class GwUtils {

    /**
     * 返回响应，默认关闭连接
     * @param context
     * @param status
     * @param message
     */
    public static void sendResponse(GatewayContext context, HttpResponseStatus status, String message) {
        sendResponse(context.getCtx(), status, message, true);
    }

    /**
     * 返回响应
     * @param ctx
     * @param status
     * @param message
     */
    public static void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message, boolean closeConnection) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(message, CharsetUtil.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        if (closeConnection) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.writeAndFlush(response);
        }
    }

}
