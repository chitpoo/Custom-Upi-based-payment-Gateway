package com.example.upigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UpiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(UpiGatewayApplication.class, args);
    }
}
