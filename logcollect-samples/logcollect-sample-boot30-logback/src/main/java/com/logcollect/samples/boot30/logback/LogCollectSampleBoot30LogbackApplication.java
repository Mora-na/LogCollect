package com.logcollect.samples.boot30.logback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.logcollect.samples.app", "com.logcollect.samples.shared"})
public class LogCollectSampleBoot30LogbackApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogCollectSampleBoot30LogbackApplication.class, args);
    }
}
