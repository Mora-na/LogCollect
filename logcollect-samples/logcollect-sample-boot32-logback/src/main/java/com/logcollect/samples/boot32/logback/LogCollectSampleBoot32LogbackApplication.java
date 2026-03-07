package com.logcollect.samples.boot32.logback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.logcollect.samples.app", "com.logcollect.samples.shared"})
public class LogCollectSampleBoot32LogbackApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogCollectSampleBoot32LogbackApplication.class, args);
    }
}
