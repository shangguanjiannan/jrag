package io.github.jerryt92.jrag.config;

import io.github.jerryt92.jrag.service.llm.client.DynamicLlmClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmClientConfig {

    private final LlmProperties llmProperties;

    public LlmClientConfig(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @Bean
    public DynamicLlmClient llmClient() {
        return new DynamicLlmClient(llmProperties);
    }
}
