package com.logcollect.samples.boot32.log4j2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.logcollect.samples.app", "com.logcollect.samples.shared"})
public class LogCollectSampleBoot32Log4j2Application {

    public static void main(String[] args) {
        SpringApplication.run(LogCollectSampleBoot32Log4j2Application.class, args);
    }
}
