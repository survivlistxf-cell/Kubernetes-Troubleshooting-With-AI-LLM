package com.kdiag.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiServerApplication {
    public static void main(String[] args) {
        try {
            SpringApplication.run(AiServerApplication.class, args);
        } catch (Throwable t) {
            System.err.println("FATAL ERROR DURING STARTUP:");
            t.printStackTrace();
            System.exit(1);
        }
    }
}
