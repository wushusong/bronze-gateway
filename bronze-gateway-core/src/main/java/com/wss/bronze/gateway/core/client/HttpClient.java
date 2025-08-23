package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.config.ApplicationContextHolder;
import com.wss.bronze.gateway.core.config.GatewayProperties;
import com.wss.bronze.gateway.core.resilience.CircuitBreakerManager;
import com.wss.bronze.gateway.core.resilience.FallbackHandler;
import com.wss.bronze.gateway.core.resilience.ResilienceException;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 改进的HTTP客户端，支持连接池、重试机制和更好的错误处理
 *
 * @author wss
 */
@Slf4j
@DependsOn(value = "applicationContextHolder")
@Component
public class HttpClient implements DisposableBean {

    // 定义通道属性键
    public static final AttributeKey<GatewayContext> GATEWAY_CONTEXT_KEY =
            AttributeKey.valueOf("gatewayContext");
    public static final AttributeKey<FixedChannelPool> CHANNEL_POOL_KEY =
            AttributeKey.valueOf("channelPool");

    private final EventLoopGroup group;

    private final GatewayProperties properties;
    private final long connectTimeoutMs;
    private final int maxRetries;
    // 连接池映射，按 host:port 维度缓存
    private final Map<String, FixedChannelPool> channelPoolMap = new ConcurrentHashMap<>();
    // 每个主机的最大并发连接数（如果没有配置，使用合理默认值）
    private final int maxConnectionsPerHost;
    private final int maxPendingAcquires;

    // URI缓存，避免重复解析
    private final Map<String, URI> uriCache = new ConcurrentHashMap<>();

    public HttpClient() {
        this.properties = ApplicationContextHolder.getBean(GatewayProperties.class);
        // 根据CPU核心数优化EventLoopGroup线程数
        int threadCount = Math.max(Runtime.getRuntime().availableProcessors() * 4, properties.getCpuMaxThreadCount());
        this.group = new NioEventLoopGroup(threadCount, new DefaultThreadFactory("Gateway-Worker", true));
        this.connectTimeoutMs = properties.getConnectTimeoutMs() > 0 ? properties.getConnectTimeoutMs() : 5000;
        this.maxRetries = properties.getMaxRetries() > 0 ? properties.getMaxRetries() : -1;
        // 大幅增加每个主机的最大连接数，支持高并发
        this.maxConnectionsPerHost = properties.getMaxConnectionsPerHost() > 0 ? properties.getMaxConnectionsPerHost() : 500;
        // 增加等待队列大小
        this.maxPendingAcquires = properties.getMaxPendingAcquires() > 0 ? properties.getMaxPendingAcquires() : 20000;

        // 新增内存分配优化配置
        System.setProperty("io.netty.allocator.numHeapArenas", "64");
        System.setProperty("io.netty.allocator.numDirectArenas", "64");
        System.setProperty("io.netty.allocator.pageSize", "8192");
        System.setProperty("io.netty.allocator.maxOrder", "10");

        log.info("HttpClient initialized with maxConnectionsPerHost: {}, maxPendingAcquires: {}, threadCount: {}",
                maxConnectionsPerHost, maxPendingAcquires, threadCount);
    }

    /**
     * 转发请求到后端服务
     */
    public void forward(GatewayContext context, String url, boolean resilienceFlag,
                        String serviceId, CircuitBreakerManager circuitBreakerManager,
                        FallbackHandler fallbackHandler) {
        try {
            if (resilienceFlag && circuitBreakerManager != null) {
                executeWithCircuitBreaker(context, url, serviceId, circuitBreakerManager, fallbackHandler);
            } else {
                executeRequest(context, url, 0, false, null, null, null);
            }
        } catch (Exception e) {
            log.error("Failed to forward request to: {}", url, e);
            handleError(context, e, resilienceFlag, serviceId, fallbackHandler);
        }
    }

    /**
     * 使用熔断器执行请求
     */
    private void executeWithCircuitBreaker(GatewayContext context, String url,
                                           String serviceId, CircuitBreakerManager circuitBreakerManager,
                                           FallbackHandler fallbackHandler) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(serviceId);

        try {
            Supplier<Void> supplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
                executeRequest(context, url, 0, true, circuitBreaker, fallbackHandler, serviceId);
                return null;
            });
            supplier.get();
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker is open for service: {}", serviceId);
            fallbackHandler.handleFallback(context, serviceId, "Call not permitted by circuit breaker");
        } catch (Exception e) {
            log.error("Service call failed for service: {}", serviceId, e);
            fallbackHandler.handleFallback(context, serviceId, "Service call failed: " + e.getMessage());
        }
    }

    /**
     * 执行HTTP请求，支持重试机制（使用连接池获取通道）
     */
    private void executeRequest(GatewayContext context, String url, int retryCount, boolean resilienceFlag, CircuitBreaker circuitBreaker, FallbackHandler fallbackHandler, String serviceId) {
        try {
            URI uri = getCachedURI(url);
            String host = uri.getHost();
            int port = getPort(uri);

            // 从连接池获取连接
            FixedChannelPool pool = getOrCreatePool(host, port);
            pool.acquire().addListener((FutureListener<Channel>) acquireFuture -> {
                if (!acquireFuture.isSuccess()) {
                    //连接失败
                    log.error("Failed to acquire channel from pool for {}:{}", host, port, acquireFuture.cause());
                    handleRequestError(context, url, retryCount, resilienceFlag, circuitBreaker, fallbackHandler, serviceId, acquireFuture.cause());
                    return;
                }
                Channel channel = acquireFuture.getNow();
                try {
                    // 在通道上存储连接池引用，用于后续释放
                    channel.attr(CHANNEL_POOL_KEY).set(pool);

                    // 存储上下文
                    channel.attr(GATEWAY_CONTEXT_KEY).set(context);

                    sendRequest(channel, context, url, uri);
                } catch (Exception e) {
                    log.error("Failed to send request to: {}", url, e);
                    // 使用安全的连接释放方法
                    releaseChannel(channel);
                    handleRequestError(context, url, retryCount, resilienceFlag, circuitBreaker, fallbackHandler, serviceId, e);
                }
            });
        } catch (URISyntaxException e) {
            log.error("Invalid URL: {}", url, e);
            if (!resilienceFlag) {
                GwUtils.sendResponse(context, HttpResponseStatus.BAD_REQUEST,
                        String.format("Invalid URL:%s,%s", url, e.getMessage()));
            } else {
                throw new ResilienceException(String.format("Invalid URL:%s,%s", url, e.getMessage()));
            }
        } catch (ResilienceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error while executing request to: {}", url, e);
            handleRequestError(context, url, retryCount, resilienceFlag, circuitBreaker, fallbackHandler, serviceId, e);
        }
    }

    private void doResilienceNum(GatewayContext context, CircuitBreaker circuitBreaker, FallbackHandler fallbackHandler, String serviceId, String msg) {
        try {
            // 执行HTTP调用，让熔断器自动处理权限检查和状态管理
            Supplier<Void> supplier = CircuitBreaker.decorateSupplier(
                    circuitBreaker, () -> {
                        throw new ResilienceException(msg);
                    }
            );
            supplier.get();
        } catch (CallNotPermittedException e) {
            log.warn("CircuitBreakerDecorator Circuit breaker is open for service: {}", serviceId);
            fallbackHandler.handleFallback(context, serviceId, "Call not permitted by circuit breaker");
        } catch (Exception e) {
            log.error("CircuitBreakerDecorator Service call failed for service: {}", serviceId, e);
            fallbackHandler.handleFallback(context, serviceId, "Service call failed: " + e.getMessage());
        }
    }

    /**
     * 处理请求错误，支持重试
     */
    private void handleRequestError(GatewayContext context, String url, int retryCount, boolean resilienceFlag, CircuitBreaker circuitBreaker, FallbackHandler fallbackHandler, String serviceId, Throwable error) {
        if (maxRetries <= 0) {
            if (!resilienceFlag) {
                GwUtils.sendResponse(context, HttpResponseStatus.BAD_GATEWAY,
                        "Service unavailable: " + error.getMessage());
            } else {
                doResilienceNum(context, circuitBreaker, fallbackHandler, serviceId, "Service unavailable: " + error.getMessage());
            }
            return;
        }
        if (retryCount < maxRetries) {
            log.info("Retrying request to {} (attempt {}/{})", url, retryCount + 1, maxRetries);
            // 指数退避重试，避免立即重试
            long delay = Math.min(1000L * (1L << retryCount), 10000L); // 最大10秒延迟
            group.schedule(() -> executeRequest(context, url, retryCount + 1, resilienceFlag,
                            circuitBreaker, fallbackHandler, serviceId),
                    delay, TimeUnit.MILLISECONDS);
        } else {
            log.error("Max retries exceeded for request to: {}", url);
            if (!resilienceFlag) {
                GwUtils.sendResponse(context, HttpResponseStatus.BAD_GATEWAY,
                        "Service unavailable after " + maxRetries + " retries: " + error.getMessage());
            } else {
                doResilienceNum(context, circuitBreaker, fallbackHandler, serviceId, "Service unavailable after " + maxRetries + " retries: " + error.getMessage());
            }
        }
    }

    /**
     * 发送HTTP请求
     */
    private void sendRequest(Channel channel, GatewayContext context, String url, URI uri) {
        try {
            FullHttpRequest request = buildRequest(context, url, uri);

            // 发送请求
            channel.writeAndFlush(request).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    log.error("Failed to write request to backend: {}", url, writeFuture.cause());
                    GwUtils.sendResponse(context, HttpResponseStatus.BAD_GATEWAY,
                            "Backend write failed: " + writeFuture.cause().getMessage());
                    releaseChannel(channel);
                }
            });

            // 设置响应超时
            channel.eventLoop().schedule(() -> {
                if (channel.isActive()) {
                    log.warn("Request timeout for: {}", url);
                    GwUtils.sendResponse(context, HttpResponseStatus.GATEWAY_TIMEOUT,
                            "Request timeout");
                    releaseChannel(channel);
                }
            }, properties.getBackendResponseTimeoutMs(), TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            log.error("Error building or sending request to: {}", url, e);
            GwUtils.sendResponse(context, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal error: " + e.getMessage());
            releaseChannel(channel);
        }
    }

    /**
     * 安全释放连接回连接池
     */
    private void releaseChannel(Channel channel) {
        if (channel == null) {
            return;
        }

        try {
            // 清理通道属性
            channel.attr(GATEWAY_CONTEXT_KEY).set(null);

            FixedChannelPool pool = channel.attr(CHANNEL_POOL_KEY).getAndSet(null);
            if (pool != null && channel.isActive()) {
                pool.release(channel);
            } else {
                channel.close();
            }
        } catch (Exception e) {
            log.warn("Failed to release channel to pool, closing instead", e);
            try {
                channel.close();
            } catch (Exception closeException) {
                log.warn("Failed to close channel", closeException);
            }
        }
    }

    /**
     * 构建HTTP请求
     */
    private FullHttpRequest buildRequest(GatewayContext context, String url, URI uri) {
        FullHttpRequest originalRequest = context.getRequest();
        FullHttpRequest request = originalRequest.copy();

        // 构建新的URI
        String newUri = buildTargetUri(originalRequest.uri(), url);
        request.setUri(newUri);

        // 设置Host头
        request.headers().set(HttpHeaderNames.HOST, uri.getHost() + ":" + uri.getPort());

        // 禁用keep-alive
        HttpUtil.setKeepAlive(request, true);

        // 添加超时头
        request.headers().set("X-Request-Timeout",
                String.valueOf(properties.getBackendResponseTimeoutMs()));

        return request;
    }

    /**
     * 获取缓存的URI对象
     */
    private URI getCachedURI(String url) throws URISyntaxException {
        return uriCache.computeIfAbsent(url, key -> {
            try {
                return new URI(key);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 构建目标URI
     */
    private String buildTargetUri(String originalUri, String targetUrl) {
        try {
            URI target = getCachedURI(targetUrl);
            // 简化处理逻辑，避免不必要的字符串操作
            StringBuilder newUri = new StringBuilder();
            newUri.append(target.getScheme()).append("://")
                    .append(target.getHost());
            if (target.getPort() > 0) {
                newUri.append(":").append(target.getPort());
            }
            newUri.append("/");
            newUri.append(Arrays.stream(StringUtils.tokenizeToStringArray(originalUri, "/"))
                    .skip(1L)
                    .collect(Collectors.joining("/"))); // 直接使用原始URI路径
            return newUri.toString();
        } catch (URISyntaxException e) {
            log.error("Error building target URI", e);
            return targetUrl + "/" + originalUri;
        }
    }

    /**
     * 创建或获取指定 host:port 的连接池
     */
    private FixedChannelPool getOrCreatePool(String host, int port) {
        String key = host + ":" + port;
        return channelPoolMap.computeIfAbsent(key, k -> {
            Bootstrap bootstrap = createBootstrap(host, port);
            bootstrap.option(ChannelOption.SO_RCVBUF, properties.getSoRcvbuf());
            bootstrap.option(ChannelOption.SO_SNDBUF, properties.getSoSndbuf());

            // 增加连接保活配置（新增以下参数）
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs()); // 缩短连接超时
//            bootstrap.option(ChannelOption.TCP_NODELAY, true);
            ChannelPoolHandler poolHandler = new ChannelPoolHandler() {
                @Override
                public void channelReleased(Channel ch) {
                    // 连接释放时的清理工作
                    ch.attr(GATEWAY_CONTEXT_KEY).remove();
                    ch.attr(CHANNEL_POOL_KEY).remove();
                }

                @Override
                public void channelAcquired(Channel ch) {
                    // 连接获取时的检查
                    if (!ch.isActive()) {
                        log.warn("Acquired inactive channel, closing it");
                        ch.close();
                    }
                }

                @Override
                public void channelCreated(Channel ch) {
                    SocketChannel sc = (SocketChannel) ch;
                    HttpClientHandler httpClientHandler = new HttpClientHandler();
                    httpClientHandler.setCLIENT_WRITE_TIMEOUT_MS(properties.getClientWriteTimeoutMs());
                    httpClientHandler.setBACKEND_RESPONSE_TIMEOUT_MS(properties.getBackendResponseTimeoutMs());

                    sc.pipeline()
                            .addLast(new HttpClientIdleStateHandler())
                            .addLast(new HttpClientCodec())
                            .addLast(new HttpObjectAggregator(properties.getMaxContentLength()))
                            .addLast(httpClientHandler);

                    // 添加连接关闭监听器，确保连接被正确释放
                    ch.closeFuture().addListener((ChannelFutureListener) cf -> {
                        try {
                            FixedChannelPool pool = ch.attr(CHANNEL_POOL_KEY).get();
                            if (pool != null) {
                                pool.release(ch);
                            }
                        } catch (Exception e) {
                            log.warn("Error releasing channel on close", e);
                        }
                    });
                }
            };
            return new FixedChannelPool(
                    bootstrap,
                    poolHandler,
                    ChannelHealthChecker.ACTIVE,
                    FixedChannelPool.AcquireTimeoutAction.NEW,
                    connectTimeoutMs,
                    maxConnectionsPerHost,
                    maxPendingAcquires,
                    true,
                    //关闭公平锁，提升并发性能
                    false
            );
        });
    }

    /**
     * 创建Bootstrap（不再在此处附加业务处理器，由连接池处理器完成）
     */
    private Bootstrap createBootstrap(String host, int port) {
        return new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMs)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_LINGER, 0)
                .option(ChannelOption.SO_RCVBUF, 65536)
                .option(ChannelOption.SO_SNDBUF, 65536)
                .remoteAddress(new InetSocketAddress(host, port));
    }

    /**
     * 获取端口号
     */
    private int getPort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }


    /**
     * 处理错误
     */
    private void handleError(GatewayContext context, Exception e, boolean resilienceFlag,
                             String serviceId, FallbackHandler fallbackHandler) {
        if (resilienceFlag && fallbackHandler != null) {
            fallbackHandler.handleFallback(context, serviceId, "Request failed: " + e.getMessage());
        } else {
            GwUtils.sendResponse(context, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error: " + e.getMessage());
        }
    }

    /**
     * 获取连接池状态信息
     */
    public void logPoolStatus() {
        for (Map.Entry<String, FixedChannelPool> entry : channelPoolMap.entrySet()) {
            FixedChannelPool pool = entry.getValue();
            log.info("Pool {}: acquired={}",
                    entry.getKey(),
                    pool.acquiredChannelCount());
        }
    }

    /**
     * 关闭客户端
     */
    public void shutdown() {
        log.info("Shutting down HttpClient with {} pools", channelPoolMap.size());

        // 先关闭连接池
        channelPoolMap.values().parallelStream().forEach(pool -> {
            try {
                pool.close();
            } catch (Exception e) {
                log.warn("Error closing channel pool", e);
            }
        });
        channelPoolMap.clear();

        // 清理URI缓存
//        uriCache.clear();

        // 关闭EventLoopGroup
        if (group != null && !group.isShutdown()) {
            try {
                group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while shutting down EventLoopGroup", e);
            }
        }
    }

    @Override
    public void destroy() {
        shutdown();
    }
}
