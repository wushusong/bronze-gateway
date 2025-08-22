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
import java.util.concurrent.CompletableFuture;
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

    private final EventLoopGroup group;

    private final GatewayProperties properties;
    private final long connectTimeoutMs;
    private final int maxRetries;
    // 连接池映射，按 host:port 维度缓存
    private final Map<String, FixedChannelPool> channelPoolMap = new ConcurrentHashMap<>();
    // 每个主机的最大并发连接数（如果没有配置，使用合理默认值）
    private final int maxConnectionsPerHost;
    private final int maxPendingAcquires;

    public HttpClient() {
        this.properties = ApplicationContextHolder.getBean(GatewayProperties.class);
        this.group = new NioEventLoopGroup();
        this.connectTimeoutMs = properties.getConnectTimeoutMs() > 0 ? properties.getConnectTimeoutMs() : 5000;
        this.maxRetries = properties.getMaxRetries() > 0 ? properties.getMaxRetries() : -1;
        this.maxConnectionsPerHost = properties.getMaxConnectionsPerHost() > 0 ? properties.getMaxConnectionsPerHost() : 64;
        this.maxPendingAcquires = properties.getMaxPendingAcquires() > 0 ? properties.getMaxPendingAcquires() : 10000;
    }

    /**
     * 转发请求到后端服务
     */
    public void forward(GatewayContext context, String url, boolean resilienceFlag,
                        String serviceId, CircuitBreakerManager circuitBreakerManager,
                        FallbackHandler fallbackHandler) {

        CompletableFuture.runAsync(() -> {
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
        });
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
            URI uri = new URI(url);
            String host = uri.getHost();
            int port = getPort(uri);
            String poolKey = host + ":" + port;

            // 从连接池获取连接
            FixedChannelPool pool = getOrCreatePool(host, port);
            pool.acquire().addListener((FutureListener<Channel>) acquireFuture -> {
                if (!acquireFuture.isSuccess()) {
                    log.error("Failed to acquire channel from pool: {}", poolKey, acquireFuture.cause());
                    handleRequestError(context, url, retryCount, resilienceFlag, circuitBreaker, fallbackHandler, serviceId, acquireFuture.cause());
                    return;
                }
                Channel channel = acquireFuture.getNow();
                // 在通道关闭时确保释放回连接池
                channel.closeFuture().addListener((ChannelFutureListener) cf -> {
                    try {
                        pool.release(channel);
                    } catch (Exception releaseEx) {
                        log.warn("Failed to release channel to pool: {}", poolKey, releaseEx);
                    }
                });

                try {
                    sendRequest(channel, context, url, uri);
                } catch (Exception e) {
                    log.error("Failed to send request to: {}", url, e);
                    // 主动关闭，触发 closeFuture 中的释放
                    channel.close();
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
            // 延迟重试，避免立即重试
            group.schedule(() -> executeRequest(context, url, retryCount + 1, resilienceFlag, circuitBreaker, fallbackHandler, serviceId),
                    (retryCount + 1) * 1000L, TimeUnit.MILLISECONDS);
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

            // 存储上下文
            channel.attr(GATEWAY_CONTEXT_KEY).set(context);

            // 发送请求
            channel.writeAndFlush(request).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    log.error("Failed to write request to backend: {}", url, writeFuture.cause());
                    GwUtils.sendResponse(context, HttpResponseStatus.BAD_GATEWAY,
                            "Backend write failed: " + writeFuture.cause().getMessage());
                    channel.close();
                }
            });

            // 设置响应超时
            channel.eventLoop().schedule(() -> {
                if (channel.isActive()) {
                    log.warn("Request timeout for: {}", url);
                    GwUtils.sendResponse(context, HttpResponseStatus.GATEWAY_TIMEOUT,
                            "Request timeout");
                    channel.close();
                }
            }, properties.getBackendResponseTimeoutMs(), TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            log.error("Error building or sending request to: {}", url, e);
            GwUtils.sendResponse(context, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal error: " + e.getMessage());
            channel.close();
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
        request.headers().set(HttpHeaderNames.HOST, uri.getHost());

        // 禁用keep-alive
        HttpUtil.setKeepAlive(request, false);

        // 添加超时头
        request.headers().set("X-Request-Timeout",
                String.valueOf(properties.getBackendResponseTimeoutMs()));

        return request;
    }

    /**
     * 构建目标URI
     */
    private String buildTargetUri(String originalUri, String targetUrl) {
        try {
            URI original = new URI(originalUri);
            URI target = new URI(targetUrl);

            // 提取路径部分
            String path = original.getPath();
            if (path != null && path.startsWith("/")) {
                path = path.substring(1);
            }
            path = Arrays.stream(StringUtils.tokenizeToStringArray(path, "/"))
                    .skip(1L)
                    .collect(Collectors.joining("/"));

            // 构建新的URI
            StringBuilder newUri = new StringBuilder();
            newUri.append(target.getScheme()).append("://");
            newUri.append(target.getHost());
            if (target.getPort() > 0) {
                newUri.append(":").append(target.getPort());
            }
            newUri.append("/").append(path);

            // 添加查询参数
            if (original.getQuery() != null) {
                newUri.append("?").append(original.getQuery());
            }

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
            ChannelPoolHandler poolHandler = new ChannelPoolHandler() {
                @Override
                public void channelReleased(Channel ch) {
                    // no-op
                }

                @Override
                public void channelAcquired(Channel ch) {
                    // no-op
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
                }
            };
            return new FixedChannelPool(
                    bootstrap,
                    poolHandler,
                    ChannelHealthChecker.ACTIVE,
                    FixedChannelPool.AcquireTimeoutAction.FAIL,
                    connectTimeoutMs,
                    maxConnectionsPerHost,
                    maxPendingAcquires,
                    true
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
     * 关闭客户端
     */
    public void shutdown() {
        try {
            // 先关闭连接池
            for (Map.Entry<String, FixedChannelPool> entry : channelPoolMap.entrySet()) {
                try {
                    entry.getValue().close();
                } catch (Exception e) {
                    log.warn("Error closing channel pool: {}", entry.getKey(), e);
                }
            }
            channelPoolMap.clear();
            // 关闭EventLoopGroup
            if (group != null && !group.isShutdown()) {
                group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            }
        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }

    @Override
    public void destroy() {
        shutdown();
    }
}
