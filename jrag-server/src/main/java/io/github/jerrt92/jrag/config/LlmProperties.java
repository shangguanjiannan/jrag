package io.github.jerrt92.jrag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LlmProperties {
    @Value("${jrag.llm.demo}")
    public Boolean demo;
    @Value("${jrag.llm.llm-provider}")
    public String llmProvider;
    @Value("${jrag.llm.ollama.model-name}")
    public String ollamaModelName;
    @Value("${jrag.llm.ollama.base-url}")
    public String ollamaBaseUrl;
    @Value("${jrag.llm.ollama.keep_alive_seconds}")
    public int ollamaKeepAliveSeconds;
    @Value("${jrag.llm.ollama.context-length}")
    public int ollamaContextLength;
    @Value("${jrag.llm.temperature}")
    public double temperature;
}
