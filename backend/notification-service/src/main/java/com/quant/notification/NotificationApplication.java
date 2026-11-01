package com.quant.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.cloud.openfeign.EnableFeignClients
@SpringBootApplication
public class NotificationApplication {
    public static void main(String[] args) { SpringApplication.run(NotificationApplication.class, args); }
}
