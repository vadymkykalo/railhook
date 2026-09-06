package com.webhook.platform.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.webhook.platform.worker",
        "com.webhook.platform.common"
})
@EnableScheduling
public class WebhookPlatformWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebhookPlatformWorkerApplication.class, args);
    }
}
