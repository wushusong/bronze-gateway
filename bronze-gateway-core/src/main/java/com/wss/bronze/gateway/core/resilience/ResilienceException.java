package com.wss.bronze.gateway.core.resilience;

import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ResilienceException extends RuntimeException{

    private HttpResponseStatus status;
    private String msg;

    public ResilienceException(HttpResponseStatus status, String msg) {
        super(msg);
        this.status = status;
        this.msg = msg;
    }

    public ResilienceException(String msg) {
        super(msg);
        this.status = HttpResponseStatus.SERVICE_UNAVAILABLE;
        this.msg = msg;
    }

}
