package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 优化后的HTTP客户端处理器
 * 优化点：
 * 1. 减少定时器创建，使用更高效的超时处理
 * 2. 改进内存管理，防止内存泄漏
 * 3. 优化连接释放机制
 * 4. 简化处理流程，提升性能
 *
 * @author wss
 */
@Setter
@Slf4j
public class HttpClientHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

    private long BACKEND_RESPONSE_TIMEOUT_MS = 5000L;
    private long CLIENT_WRITE_TIMEOUT_MS = 5000L;

    private static final AttributeKey<ScheduledFuture<?>> BACKEND_TIMEOUT_FUTURE_KEY =
            AttributeKey.valueOf("backendTimeoutFuture");
    private static final AttributeKey<ScheduledFuture<?>> CLIENT_WRITE_TIMEOUT_FUTURE_KEY =
            AttributeKey.valueOf("clientWriteTimeoutFuture");

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 使用更高效的超时处理，避免创建不必要的定时任务
        ScheduledFuture<?> timeoutFuture = ctx.executor().schedule(() -> {
            GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).getAndSet(null);
            if (gatewayContext != null) {
                log.warn("Backend response timeout ({} ms)", BACKEND_RESPONSE_TIMEOUT_MS);
                GwUtils.sendError(gatewayContext, "Upstream response timeout");
                // 安全关闭客户端连接
                safeCloseClientConnection(gatewayContext);
            }
            // 确保后端连接被关闭
            ctx.close();
        }, BACKEND_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        ctx.channel().attr(BACKEND_TIMEOUT_FUTURE_KEY).set(timeoutFuture);
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) throws Exception {
        // 获取并移除网关上下文，防止重复使用
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).getAndSet(null);

        // 取消后端响应超时定时器，避免资源泄露
        cancelTimeout(ctx, BACKEND_TIMEOUT_FUTURE_KEY);

        if (gatewayContext == null) {
            log.warn("No gateway context found for backend response, releasing response");
            ReferenceCountUtil.release(backendResponse);
            ctx.close();
            return;
        }

        try {
            // 增加引用计数而不是复制，避免不必要的内存拷贝
            FullHttpResponse response = backendResponse.retain();

            // 异步写回客户端并添加监听器处理连接释放
            ChannelFuture writeFuture = gatewayContext.getCtx().writeAndFlush(response);

            // 添加写入超时保护
            addWriteTimeoutProtection(ctx, writeFuture);

            // 添加写入完成监听器
            writeFuture.addListener(future -> {
                // 取消写入超时定时器
                cancelTimeout(ctx, CLIENT_WRITE_TIMEOUT_FUTURE_KEY);

                if (!future.isSuccess()) {
                    log.error("Failed to send response to client", future.cause());
                } else {
                    log.debug("Successfully sent response to client");
                }

                // 释放连接回池
                releaseChannelToPool(ctx);
            });
        } catch (Exception e) {
            log.error("Error processing backend response", e);
            ReferenceCountUtil.release(backendResponse);
            GwUtils.sendError(gatewayContext, e.getMessage());
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Http client error", cause);

        // 清理所有定时器
        cleanupAllTimeouts(ctx);

        // 获取网关上下文并发送错误响应
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).getAndSet(null);
        if (gatewayContext != null) {
            GwUtils.sendError(gatewayContext, cause.getMessage());
            safeCloseClientConnection(gatewayContext);
        }

        // 关闭后端连接
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 清理所有定时器，防止内存泄漏
        cleanupAllTimeouts(ctx);

        // 确保网关上下文被清理
        GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).getAndSet(null);
        if (gatewayContext != null) {
            safeCloseClientConnection(gatewayContext);
        }

        super.channelInactive(ctx);
    }

    /**
     * 添加写入超时保护
     */
    private void addWriteTimeoutProtection(ChannelHandlerContext ctx, ChannelFuture writeFuture) {
        ScheduledFuture<?> writeTimeout = ctx.executor().schedule(() -> {
            if (!writeFuture.isDone()) {
                log.warn("Client write timeout ({} ms)", CLIENT_WRITE_TIMEOUT_MS);
                GatewayContext gc = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).getAndSet(null);
                if (gc != null) {
                    GwUtils.sendError(gc, "Downstream write timeout");
                    safeCloseClientConnection(gc);
                }
                ctx.close();
            }
        }, CLIENT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        ctx.channel().attr(CLIENT_WRITE_TIMEOUT_FUTURE_KEY).set(writeTimeout);
    }

    /**
     * 取消指定的超时定时器
     */
    private void cancelTimeout(ChannelHandlerContext ctx, AttributeKey<ScheduledFuture<?>> key) {
        ScheduledFuture<?> timeout = ctx.channel().attr(key).getAndSet(null);
        if (timeout != null && !timeout.isCancelled() && !timeout.isDone()) {
            timeout.cancel(false);
        }
    }

    /**
     * 清理所有超时定时器
     */
    private void cleanupAllTimeouts(ChannelHandlerContext ctx) {
        cancelTimeout(ctx, BACKEND_TIMEOUT_FUTURE_KEY);
        cancelTimeout(ctx, CLIENT_WRITE_TIMEOUT_FUTURE_KEY);
    }

    /**
     * 安全地关闭客户端连接
     */
    private void safeCloseClientConnection(GatewayContext gatewayContext) {
        try {
            if (gatewayContext.getCtx() != null && gatewayContext.getCtx().channel().isActive()) {
                gatewayContext.getCtx().close();
            }
        } catch (Exception e) {
            log.warn("Error closing client connection", e);
        }
    }

    /**
     * 将连接释放回连接池
     */
    private void releaseChannelToPool(ChannelHandlerContext ctx) {
        try {
            // 获取连接池并释放连接
            FixedChannelPool pool = ctx.channel().attr(HttpClient.CHANNEL_POOL_KEY).getAndSet(null);
            if (pool != null && ctx.channel().isActive()) {
                pool.release(ctx.channel());
            } else {
                ctx.close();
            }
        } catch (Exception e) {
            log.warn("Failed to release channel to pool, closing instead", e);
            ctx.close();
        }
    }


}
