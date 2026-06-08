package com.sentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SentinelSdkApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelSdkApplication.class, args);
        System.out.println("""
            ╔══════════════════════════════════════════════════════════╗
            ║          SENTINEL-SDK  v2.1                              ║
            ║      Autonomous QA & Self-Healing Agent                  ║
            ╠══════════════════════════════════════════════════════════╣
            ║  Dashboard  →  http://localhost:8090                     ║
            ║  Swagger UI →  http://localhost:8090/swagger-ui.html     ║
            ║  REST API   →  http://localhost:8090/api/v1/scans        ║
            ╚══════════════════════════════════════════════════════════╝
            """);
    }
}
