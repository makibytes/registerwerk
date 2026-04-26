// Requires Java 25 and Spring Boot 4.0.5
package de.makibytes.registerwerk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
public class RegisterwerkApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegisterwerkApplication.class, args);
    }
}
