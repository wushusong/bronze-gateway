package com.wss.bronze.gateway.core.filter;

import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class FilterException extends RuntimeException{

    private HttpResponseStatus status;
    private String msg;

    public FilterException(HttpResponseStatus status, String msg) {
        super(msg);
        this.status = status;
        this.msg = msg;
    }

    public FilterException(String msg) {
        super(msg);
        this.status = HttpResponseStatus.SERVICE_UNAVAILABLE;
        this.msg = msg;
    }

}
