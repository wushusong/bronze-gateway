package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @author wss
 */
@Slf4j
@Component
public class HttpClient {

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
                        ch.pipeline()
                                .addLast(new HttpClientIdleStateHandler()) // 添加空闲状态检测
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(1024 * 1024))
                                .addLast(new HttpClientHandler());
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

                // 存储上下文，以便在响应时使用
                f.channel().attr(GATEWAY_CONTEXT_KEY).set(context);

                // 发送请求到后端服务
                f.channel().writeAndFlush(request);
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
}
