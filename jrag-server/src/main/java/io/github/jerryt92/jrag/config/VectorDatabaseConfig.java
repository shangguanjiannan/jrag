package io.github.jerryt92.jrag.config;

import io.github.jerryt92.jrag.model.EmbeddingModel;
import io.github.jerryt92.jrag.po.mgb.EmbeddingsItemPoWithBLOBs;
import io.github.jerryt92.jrag.service.embedding.EmbeddingService;
import io.github.jerryt92.jrag.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.jrag.service.rag.vdb.milvus.MilvusService;
import io.github.jerryt92.jrag.utils.HashUtil;
import io.milvus.v2.common.IndexParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.NoSuchAlgorithmException;
import java.util.List;

@Slf4j
@Configuration
public class VectorDatabaseConfig {

    @Value("${jrag.vector-database.provider}")
    public String vectorDatabase;
    public Integer dimension;
    @Value("${jrag.vector-database.milvus.cluster-endpoint}")
    private String milvusClusterEndpoint;
    @Value("${jrag.vector-database.milvus.collection-name}")
    private String milvusCollectionName;
    @Value("${jrag.vector-database.milvus.token}")
    private String milvusToken;
    private final EmbeddingModel.EmbeddingsRequest checkEmbeddingsRequest = new EmbeddingModel.EmbeddingsRequest()
            .setInput(List.of("test"));

    @Bean
    public VectorDatabaseService vectorDatabaseService(LlmProperties llmProperties, EmbeddingService embeddingService) {
        try {
            VectorDatabaseService vectorDatabaseService;
            // 检查嵌入模型是否变化
            EmbeddingModel.EmbeddingsItem testEmbed = embeddingService.embed(checkEmbeddingsRequest).getData().getFirst();
            dimension = testEmbed.getEmbeddings().length;
            String checkEmbeddingHash = HashUtil.getMessageDigest(testEmbed.toString().getBytes(), HashUtil.MdAlgorithm.SHA256);

            switch (vectorDatabase) {
                case "milvus":
                    vectorDatabaseService = new MilvusService(
                            milvusClusterEndpoint,
                            milvusCollectionName,
                            milvusToken,
                            dimension,
                            IndexParam.MetricType.valueOf("COSINE")
                    );
                    break;
                default:
                    throw new RuntimeException("Unknown vector database: " + vectorDatabase);
            }
            if (llmProperties.useRag) {
                List<EmbeddingsItemPoWithBLOBs> embeddingsItemPoWithBLOBs = embeddingService.checkEmbedData(checkEmbeddingHash);
                vectorDatabaseService.init(embeddingsItemPoWithBLOBs);
            }
            return vectorDatabaseService;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
