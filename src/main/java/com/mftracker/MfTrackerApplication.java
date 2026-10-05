package com.mftracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MfTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MfTrackerApplication.class, args);
    }
}
