package io.jobplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobPlatformApplication.class, args);
    }
}
