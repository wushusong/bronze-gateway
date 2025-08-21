package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.config.ApplicationContextHolder;
import com.wss.bronze.gateway.core.config.GatewayProperties;
import com.wss.bronze.gateway.core.utils.GwUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.DisposableBean;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @author wss
 */
@Slf4j
@Component
public class HttpClient implements DisposableBean {

    // 定义通道属性键
    public static final AttributeKey<GatewayContext> GATEWAY_CONTEXT_KEY =
        AttributeKey.valueOf("gatewayContext");

    private final EventLoopGroup group = new NioEventLoopGroup();
    private final Bootstrap bootstrap;

    public HttpClient() {
        bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        GatewayProperties properties = ApplicationContextHolder.getBean(GatewayProperties.class);
                        HttpClientHandler httpClientHandler = new HttpClientHandler();
                        httpClientHandler.setCLIENT_WRITE_TIMEOUT_MS(properties.getClientWriteTimeoutMs());
                        httpClientHandler.setBACKEND_RESPONSE_TIMEOUT_MS(properties.getBackendResponseTimeoutMs());

                        ch.pipeline()
                                .addLast(new HttpClientIdleStateHandler()) // 添加空闲状态检测
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(1024 * 1024))
                                .addLast(httpClientHandler);
                    }
                });
    }

    public void forward(GatewayContext context, String url) throws URISyntaxException {
        URI uri = new URI(url);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        ChannelFuture future = bootstrap.connect(host, port);
        future.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                // 复制原始请求并修改目标地址
                FullHttpRequest request = context.getRequest().copy();
                String allUrl = null;
                String uriBefore = request.uri();
                String newPath = Arrays.stream(StringUtils.tokenizeToStringArray(uriBefore, "/"))
                        .skip(1L)
                        .collect(Collectors.joining("/"));
                if(url.endsWith("/")){
                    allUrl = url + newPath;
                }else {
                    allUrl = url + "/" + newPath;
                }

                request.setUri(allUrl);
                request.headers().set(HttpHeaderNames.HOST, host);
                // 禁用 HTTP keep-alive，确保后端在响应后关闭连接
                HttpUtil.setKeepAlive(request, false);

                // 存储上下文，以便在响应时使用
                f.channel().attr(GATEWAY_CONTEXT_KEY).set(context);

                // 发送请求到后端服务，监听写失败并关闭通道
                f.channel().writeAndFlush(request).addListener(writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        log.error("Failed to write request to backend: {}", url, writeFuture.cause());
                        GwUtils.sendResponse(context, HttpResponseStatus.BAD_GATEWAY,
                                "Backend write failed: " + writeFuture.cause().getMessage());
                        f.channel().close();
                    }
                });

                // 通道关闭时清理上下文，避免对象被长期持有
                f.channel().closeFuture().addListener((ChannelFutureListener) closeFuture -> {
                    f.channel().attr(GATEWAY_CONTEXT_KEY).set(null);
                });
            } else {
                log.error("Failed to connect to backend service: {}", url, f.cause());
                GwUtils.sendResponse(context, HttpResponseStatus.BAD_GATEWAY,
                         "Cannot connect to backend: " + f.cause().getMessage());
            }
        });
    }



    public void shutdown() {
        group.shutdownGracefully();
    }

    @Override
    public void destroy() {
        shutdown();
    }
}
