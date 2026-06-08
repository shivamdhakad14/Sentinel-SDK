package com.sentinel.sdk;

import com.sentinel.service.OpenApiDiscoveryService;
import com.sentinel.service.ScanOrchestrationService;
import lombok.Data;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot Auto-Configuration for embedding Sentinel as a .jar library.
 *
 * Add to your pom.xml:
 *   <dependency>
 *     <groupId>com.sentinel</groupId>
 *     <artifactId>sentinel-sdk</artifactId>
 *     <version>2.0.0</version>
 *   </dependency>
 *
 * Then in application.properties:
 *   sentinel.sdk.enabled=true
 *   sentinel.sdk.target-url=http://localhost:8080
 *   sentinel.sdk.auto-scan-on-startup=true
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sentinel.sdk", name = "enabled", havingValue = "true")
@ComponentScan(basePackages = "com.sentinel")
@EnableConfigurationProperties(SentinelAutoConfiguration.SentinelSdkProperties.class)
public class SentinelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SentinelSdk sentinelSdk(
            ScanOrchestrationService orchestrationService,
            OpenApiDiscoveryService discoveryService,
            SentinelSdkProperties properties) {
        return new SentinelSdk(orchestrationService, discoveryService, properties);
    }

    @Data
    @ConfigurationProperties(prefix = "sentinel.sdk")
    public static class SentinelSdkProperties {
        private boolean enabled            = false;
        private String  targetUrl          = "http://localhost:8080";
        private String  targetName         = "My API";
        private boolean autoScanOnStartup  = false;
        private boolean failOnCriticalDrift = false;
        private String  outputDir          = "./sentinel-generated-tests";
        private boolean autoSubmitPr       = true;
    }
}
