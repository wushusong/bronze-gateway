package com.wss.bronze.gateway.core.client;

import com.wss.bronze.gateway.core.GatewayContext;
import com.wss.bronze.gateway.core.utils.GwUtils;
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
        // 30秒读写空闲检测
        super(0, 0, 30, TimeUnit.SECONDS);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        try {
            if (evt.state() == IdleState.ALL_IDLE) {
                // 获取网关上下文
                GatewayContext gatewayContext = ctx.channel().attr(HttpClient.GATEWAY_CONTEXT_KEY).get();

                if (gatewayContext != null) {
                    log.warn("Connection timeout to backend service");
                    GwUtils.sendTimeoutError(gatewayContext);
                }

                // 关闭连接
                ctx.close();
            }
        } finally {
            // 确保其他处理器仍能收到 Idle 事件做各自清理
            ctx.fireUserEventTriggered(evt);
            // 或者调用：super.channelIdle(ctx, evt);
        }
    }


}
