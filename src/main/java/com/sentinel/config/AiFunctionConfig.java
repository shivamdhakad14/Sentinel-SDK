package com.sentinel.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 1.0.x ChatClient configuration.
 *
 * KEY CHANGES from 0.8.x:
 * - No Function<> bean registration — tools use @Tool annotation instead
 * - .tools(instance) passed at call-site, not at builder level
 * - ChatClient.Builder is auto-configured by Spring AI starter
 * - No more .defaultFunctions() on the builder
 */
@Configuration
public class AiFunctionConfig {

    /**
     * Plain ChatClient — tools are passed per-call via .tools(agentTools).
     * Spring AI 1.0.x: ChatClient.Builder is auto-injected by the starter.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("""
                You are Sentinel, an autonomous API quality assurance agent.
                You are precise, systematic, and always respond with valid JSON when asked.
                When analyzing APIs, you think about data dependencies between endpoints.
                """)
            .build();
    }
}
