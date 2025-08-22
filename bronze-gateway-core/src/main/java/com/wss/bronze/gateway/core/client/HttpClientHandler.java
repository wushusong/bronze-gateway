package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author wss
 */
@Setter
@Slf4j
public class HttpClientHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

    private long BACKEND_RESPONSE_TIMEOUT_MS = 5000L;

    private long CLIENT_WRITE_TIMEOUT_MS = 5000L;
    private static final AttributeKey<ScheduledFuture<?>> BACKEND_TIMEOUT_FUTURE_KEY = AttributeKey.valueOf("backendTimeoutFuture");
    private static final AttributeKey<ScheduledFuture<?>> CLIENT_WRITE_TIMEOUT_FUTURE_KEY = AttributeKey.valueOf("clientWriteTimeoutFuture");

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ScheduledFuture<?> timeoutFuture = ctx.executor().schedule(() -> {
            GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();
            log.warn("Backend response timeout ({} ms)", BACKEND_RESPONSE_TIMEOUT_MS);
            if (gatewayContext != null) {
                GwUtils.sendError(gatewayContext, "Upstream response timeout");
                try {
                    gatewayContext.getCtx().close();
                } catch (Exception ignore) {}
            }
            ctx.close();
        }, BACKEND_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ctx.channel().attr(BACKEND_TIMEOUT_FUTURE_KEY).set(timeoutFuture);
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) throws Exception {
        // 获取网关上下文
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();

        // 收到后端响应，取消后端响应超时定时器
        ScheduledFuture<?> respTimeout = ctx.channel().attr(BACKEND_TIMEOUT_FUTURE_KEY).getAndSet(null);
        if (respTimeout != null) {
            respTimeout.cancel(false);
        }

        if (gatewayContext != null) {
            try {
                // 复制响应，因为原始响应可能会被释放
                FullHttpResponse response = backendResponse.copy();

                // 将后端服务的响应写回给原始客户端，并为写入添加超时保护
                ChannelFuture writeFuture = gatewayContext.getCtx().writeAndFlush(response);

                ScheduledFuture<?> writeTimeout = ctx.executor().schedule(() -> {
                    if (!writeFuture.isDone()) {
                        log.warn("Client write timeout ({} ms)", CLIENT_WRITE_TIMEOUT_MS);
                        GatewayContext gc = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();
                        if (gc != null) {
                            GwUtils.sendError(gc, "Downstream write timeout");
                            try {
                                gc.getCtx().close();
                            } catch (Exception ignore) {}
                        }
                        ctx.close();
                    }
                }, CLIENT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                ctx.channel().attr(CLIENT_WRITE_TIMEOUT_FUTURE_KEY).set(writeTimeout);

                writeFuture.addListener(future -> {
                    // 取消写入超时定时器
                    ScheduledFuture<?> wt = ctx.channel().attr(CLIENT_WRITE_TIMEOUT_FUTURE_KEY).getAndSet(null);
                    if (wt != null) {
                        wt.cancel(false);
                    }

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

        // 取消所有可能存在的定时器，避免资源泄露
        ScheduledFuture<?> respTimeout = ctx.channel().attr(BACKEND_TIMEOUT_FUTURE_KEY).getAndSet(null);
        if (respTimeout != null) {
            respTimeout.cancel(false);
        }
        ScheduledFuture<?> writeTimeout = ctx.channel().attr(CLIENT_WRITE_TIMEOUT_FUTURE_KEY).getAndSet(null);
        if (writeTimeout != null) {
            writeTimeout.cancel(false);
        }

        // 获取网关上下文并发送错误响应
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();
        if (gatewayContext != null) {
            GwUtils.sendError(gatewayContext, cause.getMessage());
        }

        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 通道关闭时清理定时任务
        ScheduledFuture<?> respTimeout = ctx.channel().attr(BACKEND_TIMEOUT_FUTURE_KEY).getAndSet(null);
        if (respTimeout != null) {
            respTimeout.cancel(false);
        }
        ScheduledFuture<?> writeTimeout = ctx.channel().attr(CLIENT_WRITE_TIMEOUT_FUTURE_KEY).getAndSet(null);
        if (writeTimeout != null) {
            writeTimeout.cancel(false);
        }
        super.channelInactive(ctx);
    }




}
