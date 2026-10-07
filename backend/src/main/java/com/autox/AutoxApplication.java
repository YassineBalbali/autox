package com.autox;

import com.autox.auth.throttle.LoginThrottleProperties;
import com.autox.config.AuthProperties;
import com.autox.listing.ListingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AuthProperties.class, LoginThrottleProperties.class, ListingProperties.class})
public class AutoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoxApplication.class, args);
    }
}
