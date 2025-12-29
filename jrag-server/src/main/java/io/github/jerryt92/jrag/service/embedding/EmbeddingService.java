package io.github.jerryt92.jrag.service.embedding;

import io.github.jerryt92.jrag.config.EmbeddingProperties;
import io.github.jerryt92.jrag.model.EmbeddingModel;
import io.github.jerryt92.jrag.model.ollama.OllamaModel;
import io.github.jerryt92.jrag.model.openai.OpenAIModel;
import io.github.jerryt92.jrag.utils.HashUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class EmbeddingService {
    // 用于标记数据的嵌入模型
    @Getter
    private String checkEmbeddingHash;
    @Getter
    private Integer dimension;
    private final EmbeddingModel.EmbeddingsRequest checkEmbeddingsRequest = new EmbeddingModel.EmbeddingsRequest().setInput(List.of("test"));
    private final EmbeddingProperties embeddingProperties;
    private final WebClient webClient;
    private final String embeddingsPath;

    public EmbeddingService(@Autowired EmbeddingProperties embeddingProperties) {
        this.embeddingProperties = embeddingProperties;
        SslContext sslContext;
        try {
            // 配置忽略 SSL 证书校验
            sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            SslContext finalSslContext = sslContext;

            // 创建 HttpClient
            HttpClient httpClient = HttpClient.create()
                    .secure(t -> t.sslContext(finalSslContext));

            // 根据配置构建 WebClient
            WebClient.Builder builder = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient));

            switch (embeddingProperties.embeddingProvider) {
                case "open-ai":
                    this.webClient = builder
                            .baseUrl(embeddingProperties.openAiBaseUrl)
                            .defaultHeader("Authorization", "Bearer " + embeddingProperties.openAiKey)
                            .build();
                    this.embeddingsPath = embeddingProperties.embeddingsPath;
                    break;
                case "ollama":
                default:
                    this.webClient = builder
                            .baseUrl(embeddingProperties.ollamaBaseUrl)
                            .build();
                    this.embeddingsPath = "/api/embed";
                    break;
            }
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    public void init() {
        try {
            // 检查嵌入模型是否变化
            EmbeddingModel.EmbeddingsResponse response = embed(checkEmbeddingsRequest);
            if (response != null && !response.getData().isEmpty()) {
                EmbeddingModel.EmbeddingsItem testEmbed = response.getData().getFirst();
                dimension = testEmbed.getEmbeddings().length;
                checkEmbeddingHash = HashUtil.getMessageDigest(testEmbed.toString().getBytes(), HashUtil.MdAlgorithm.SHA256);
            } else {
                log.warn("Init failed: Unable to fetch embedding for test input.");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public EmbeddingModel.EmbeddingsResponse embed(EmbeddingModel.EmbeddingsRequest embeddingsRequest) {
        List<EmbeddingModel.EmbeddingsItem> embeddingsItems = new ArrayList<>();

        try {
            switch (embeddingProperties.embeddingProvider) {
                case "open-ai":
                    handleOpenAIEmbeddings(embeddingsRequest, embeddingsItems);
                    break;
                case "ollama":
                default:
                    handleOllamaEmbeddings(embeddingsRequest, embeddingsItems);
                    break;
            }
        } catch (WebClientResponseException e) {
            log.error("{} API 返回错误状态码: {}, Body: {}",
                    embeddingProperties.embeddingProvider, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("调用嵌入模型失败: Provider={}", embeddingProperties.embeddingProvider, e);
        }

        return new EmbeddingModel.EmbeddingsResponse().setData(embeddingsItems);
    }

    private void handleOpenAIEmbeddings(EmbeddingModel.EmbeddingsRequest embeddingsRequest, List<EmbeddingModel.EmbeddingsItem> embeddingsItems) {
        OpenAIModel.EmbeddingRequest<List<String>> openAIEmbeddingsRequest = new OpenAIModel.EmbeddingRequest<List<String>>()
                .setModel(embeddingProperties.openAiModelName)
                .setInput(embeddingsRequest.getInput());
        // 使用 WebClient 发送请求
        OpenAIModel.EmbeddingList openAIEmbeddingsResponse = webClient.post()
                .uri(embeddingsPath)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(openAIEmbeddingsRequest) // 自动序列化为 JSON
                .retrieve()
                .bodyToMono(OpenAIModel.EmbeddingList.class)
                .block(); // 阻塞等待结果

        if (openAIEmbeddingsResponse != null && openAIEmbeddingsResponse.getData() != null) {
            for (int i = 0; i < openAIEmbeddingsResponse.getData().size(); i++) {
                embeddingsItems.add(new EmbeddingModel.EmbeddingsItem()
                        .setEmbeddingProvider(embeddingProperties.embeddingProvider)
                        .setEmbeddingModel(embeddingProperties.openAiModelName)
                        .setCheckEmbeddingHash(checkEmbeddingHash)
                        .setText(embeddingsRequest.getInput().get(i))
                        .setEmbeddings(openAIEmbeddingsResponse.getData().get(i).getEmbedding()));
            }
        }
    }

    private void handleOllamaEmbeddings(EmbeddingModel.EmbeddingsRequest embeddingsRequest, List<EmbeddingModel.EmbeddingsItem> embeddingsItems) {
        OllamaModel.EmbeddingsRequest ollamaEmbeddingsRequest =
                new OllamaModel.EmbeddingsRequest(embeddingProperties.ollamaModelName)
                        .setInput(embeddingsRequest.getInput())
                        .setKeepAlive(embeddingProperties.keepAliveSeconds);
        // 使用 WebClient 发送请求
        OllamaModel.EmbeddingsResponse ollamaEmbeddingsResponse = webClient.post()
                .uri(embeddingsPath)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ollamaEmbeddingsRequest) // 自动序列化为 JSON
                .retrieve()
                .bodyToMono(OllamaModel.EmbeddingsResponse.class)
                .block(); // 阻塞等待结果
        if (ollamaEmbeddingsResponse != null && ollamaEmbeddingsResponse.getEmbeddings() != null) {
            for (int i = 0; i < ollamaEmbeddingsResponse.getEmbeddings().size(); i++) {
                embeddingsItems.add(new EmbeddingModel.EmbeddingsItem()
                        .setEmbeddingProvider(embeddingProperties.embeddingProvider)
                        .setEmbeddingModel(embeddingProperties.ollamaModelName)
                        .setCheckEmbeddingHash(checkEmbeddingHash)
                        .setText(embeddingsRequest.getInput().get(i))
                        .setEmbeddings(ollamaEmbeddingsResponse.getEmbeddings().get(i)));
            }
        }
    }
}