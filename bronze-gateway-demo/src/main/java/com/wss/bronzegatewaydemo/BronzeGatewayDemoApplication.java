package com.wss.bronzegatewaydemo;

import com.wss.bronze.gateway.core.annotation.WssResilienceEnable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@WssResilienceEnable
@SpringBootApplication
public class BronzeGatewayDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BronzeGatewayDemoApplication.class, args);
    }

}
