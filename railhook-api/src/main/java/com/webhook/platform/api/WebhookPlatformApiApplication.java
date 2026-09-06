package com.webhook.platform.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.webhook.platform.api",
        "com.webhook.platform.common"
})
@EnableScheduling
public class WebhookPlatformApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebhookPlatformApiApplication.class, args);
    }
}
