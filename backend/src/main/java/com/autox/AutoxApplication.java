package com.autox;

import com.autox.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class AutoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoxApplication.class, args);
    }
}
