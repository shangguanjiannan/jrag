package io.github.jerrt92.jrag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingProperties {
    @Value("${jrag.embedding.embedding-provider}")
    public String embeddingProvider;
    @Value("${jrag.embedding.ollama.model-name}")
    public String ollamaModelName;
    @Value("${jrag.embedding.ollama.base-url}")
    public String ollamaBaseUrl;
    @Value("${jrag.embedding.ollama.keep_alive_seconds}")
    public int keepAliveSeconds;
}
