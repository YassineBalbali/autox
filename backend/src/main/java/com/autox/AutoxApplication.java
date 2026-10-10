package com.autox;

import com.autox.auth.throttle.LoginThrottleProperties;
import com.autox.config.AuthProperties;
import com.autox.listing.ListingProperties;
import com.autox.listing.image.ImageProperties;
import com.autox.storage.StorageProperties;
import com.autox.throttle.PublicRateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AuthProperties.class,
        LoginThrottleProperties.class,
        ListingProperties.class,
        StorageProperties.class,
        ImageProperties.class,
        PublicRateLimitProperties.class})
public class AutoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoxApplication.class, args);
    }
}
