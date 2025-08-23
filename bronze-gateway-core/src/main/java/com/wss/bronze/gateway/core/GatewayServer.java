package com.wss.bronze.gateway.core;

import com.wss.bronze.gateway.core.config.GatewayProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 优化的网关服务器
 * 优化点：
 * 1. 提升服务器性能和吞吐量
 * 2. 改进资源管理和内存泄漏防护
 * 3. 优化连接处理和优雅关闭机制
 * 4. 增强平台兼容性和监控能力
 *
 * @author wss
 */
@Slf4j
@Component
public class GatewayServer implements ApplicationListener<ApplicationReadyEvent>, SmartLifecycle {

    @Autowired
    private GatewayProperties properties;

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile ChannelFuture serverChannelFuture;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private GatewayServerHandler gatewayServerHandler;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.info("Gateway server is already running");
            return;
        }

        try {
            log.info("Starting gateway server on port: {}", properties.getPort());

            // 创建 GatewayServerHandler 实例（单例，提高性能）
            if (gatewayServerHandler == null) {
                gatewayServerHandler = new GatewayServerHandler();
            }

            // 根据平台选择最优的EventLoopGroup实现
            ThreadFactory bossThreadFactory = new DefaultThreadFactory("gateway-boss", false);
            ThreadFactory workerThreadFactory = new DefaultThreadFactory("gateway-worker", true);

            if (isEpollAvailable()) {
                log.info("Using Epoll event loop for better performance");
                bossGroup = new EpollEventLoopGroup(1, bossThreadFactory);
                // 根据CPU核心数优化worker线程数
                int workerThreads = getOptimalWorkerThreads();
                workerGroup = new EpollEventLoopGroup(workerThreads, workerThreadFactory);
            } else {
                log.info("Using NIO event loop");
                bossGroup = new NioEventLoopGroup(1, bossThreadFactory);
                int workerThreads = getOptimalWorkerThreads();
                workerGroup = new NioEventLoopGroup(workerThreads, workerThreadFactory);
            }

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(isEpollAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast("http-codec", new HttpServerCodec())
                                    .addLast("http-aggregator", new HttpObjectAggregator(
                                            properties.getMaxContentLength() > 0 ? properties.getMaxContentLength() : 1024 * 1024))
                                    .addLast("gateway-handler", gatewayServerHandler); // 复用handler实例
                        }
                    })
                    // 服务端Socket配置
                    .option(ChannelOption.SO_BACKLOG, properties.getSoBacklog() > 0 ? properties.getSoBacklog() : 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    // 客户端Socket配置
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                            properties.getConnectTimeoutMs() > 0 ? (int) properties.getConnectTimeoutMs() : 10000);

            // 异步绑定端口，避免阻塞
            serverChannelFuture = b.bind(properties.getPort());
            serverChannelFuture.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    log.info("Gateway server started successfully on port: {}", properties.getPort());
                } else {
                    log.error("Failed to start gateway server", future.cause());
                    running.set(false);
                }
            });

        } catch (Exception e) {
            running.set(false);
            log.error("Failed to start gateway server", e);
            throw new RuntimeException("Failed to start gateway server", e);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("Gateway server is not running");
            return;
        }

        log.info("Shutting down gateway server");
        try {
            // 优雅关闭服务器通道
            if (serverChannelFuture != null && serverChannelFuture.channel().isOpen()) {
                serverChannelFuture.channel().close().syncUninterruptibly();
            }

            // 优雅关闭EventLoopGroups
            if (bossGroup != null) {
                bossGroup.shutdownGracefully(2, 15, TimeUnit.SECONDS).syncUninterruptibly();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(2, 15, TimeUnit.SECONDS).syncUninterruptibly();
            }

            log.info("Gateway server stopped successfully");
        } catch (Exception e) {
            log.error("Error during gateway server shutdown", e);
        } finally {
            bossGroup = null;
            workerGroup = null;
            serverChannelFuture = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        if (callback != null) {
            callback.run();
        }
    }

    /**
     * 检查Epoll是否可用
     */
    private boolean isEpollAvailable() {
        try {
            return Epoll.isAvailable();
        } catch (Throwable t) {
            log.debug("Epoll is not available, using NIO instead", t);
            return false;
        }
    }

    /**
     * 获取最优的worker线程数
     */
    private int getOptimalWorkerThreads() {
        int configuredThreads = properties.getWorkerThreads();
        if (configuredThreads > 0) {
            return configuredThreads;
        }

        // 默认使用Netty推荐的线程数
        int nettyThreads = NettyRuntime.availableProcessors() * 2;
        // 限制最大线程数避免资源耗尽
        return Math.min(nettyThreads, 32);
    }

    /**
     * 获取服务器性能统计信息
     */
    public String getServerStats() {
        if (gatewayServerHandler != null) {
            return gatewayServerHandler.getPerformanceStats();
        }
        return "Handler not initialized";
    }
}
