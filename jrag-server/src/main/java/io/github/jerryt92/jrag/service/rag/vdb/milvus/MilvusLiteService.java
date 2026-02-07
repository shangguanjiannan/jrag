package io.github.jerryt92.jrag.service.rag.vdb.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.github.jerryt92.jrag.model.EmbeddingModel;
import io.github.jerryt92.jrag.model.Translator;
import io.github.jerryt92.jrag.po.mgb.EmbeddingsItemPoWithBLOBs;
import io.github.jerryt92.jrag.service.rag.vdb.VectorDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class MilvusLiteService implements VectorDatabaseService {
    private final String collectionName;
    private String metricType;

    private final WebClient webClient;
    private final Gson gson; // 引入 Gson

    public MilvusLiteService(
            String clusterEndpoint,
            String collectionName,
            String token
    ) {
        this.collectionName = collectionName;
        this.gson = new Gson(); // 初始化 Gson

        String validEndpoint = clusterEndpoint.endsWith("/")
                ? clusterEndpoint.substring(0, clusterEndpoint.length() - 1)
                : clusterEndpoint;

        this.webClient = WebClient.builder()
                .baseUrl(validEndpoint)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    @Override
    public void reBuildVectorDatabase(int dimension, String metricTypeStr) {
        metricType = metricTypeStr;
        // 1. 构建 Schema
        Map<String, Object> schemaPayload = buildSchemaPayload(dimension);
        sendRequest("/collections/create", schemaPayload);
        log.info("Collection {} created/rebuilt via Python API", collectionName);
    }

    private Map<String, Object> buildSchemaPayload(int dimension) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field_name", "hash", "data_type", "VarChar", "max_length", 40, "is_primary", true, "description", "SHA-1 Hash"));
        fields.add(Map.of("field_name", "embedding_model", "data_type", "VarChar", "max_length", 128));
        fields.add(Map.of("field_name", "embedding_provider", "data_type", "VarChar", "max_length", 128));
        fields.add(Map.of(
                "field_name", "text",
                "data_type", "VarChar",
                "max_length", 4096,
                "enable_analyzer", true,
                "analyzer_params", Map.of("tokenizer", "jieba")
        ));
        fields.add(Map.of("field_name", "embedding", "data_type", "FloatVector", "dimension", dimension));
        fields.add(Map.of("field_name", "sparse", "data_type", "SparseFloatVector"));
        fields.add(Map.of("field_name", "text_chunk_id", "data_type", "VarChar", "max_length", 40));
        List<Map<String, Object>> indexes = new ArrayList<>();
        // FLAT 是最基础的索引类型，Milvus Lite 绝对支持
        indexes.add(Map.of(
                "field_name", "embedding",
                "index_type", "FLAT",
                "metric_type", this.metricType
        ));
        indexes.add(Map.of(
                "field_name", "sparse",
                "index_type", "SPARSE_INVERTED_INDEX",
                "metric_type", "BM25"
        ));

        // 再次确认：绝对不要给 hash 字段加索引，Milvus 会自动为主键创建索引

        Map<String, Object> payload = new HashMap<>();
        payload.put("collection_name", this.collectionName);
        payload.put("fields", fields);
        payload.put("indexes", indexes);
        payload.put("functions", List.of(Map.of(
                "name", "text_bm25_emb",
                "description", "将text通过BM25转换为稀疏向量",
                "function_type", "BM25",
                "input_field_names", List.of("text"),
                "output_field_names", List.of("sparse")
        )));

        return payload;
    }

    @Override
    public void initData(List<EmbeddingsItemPoWithBLOBs> embeddingsItemPos) {

        // 3. 写入数据
        if (!embeddingsItemPos.isEmpty()) {
            putData(embeddingsItemPos);
            log.info("Initialized collection {} with {} vectors", collectionName, embeddingsItemPos.size());
        }
    }

    @Override
    public void putData(List<EmbeddingsItemPoWithBLOBs> embeddingsItems) {
        // 这里 List<JsonObject> 直接兼容 Translator 返回的 Gson 对象
        List<JsonObject> milvusData = new ArrayList<>();
        for (EmbeddingsItemPoWithBLOBs item : embeddingsItems) {
            milvusData.add(Translator.translateToMilvusData(item));
        }

        if (!milvusData.isEmpty()) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("collection_name", collectionName);
            payload.put("data", milvusData);

            // Gson 会自动正确序列化包含 Gson JsonObject 的 Map
            String response = sendRequest("/vectors/upsert", payload);
            log.debug("Upsert response: {}", response);
        }
    }

    @Override
    public List<EmbeddingModel.EmbeddingsQueryItem> knnRetrieval(float[] queryVector, int topK) {
        List<JsonObject> searchResults = searchVectors(
                queryVector,
                "embedding",
                metricType,
                topK,
                List.of("hash", "embedding_model", "embedding_provider", "text", "text_chunk_id")
        );
        return toQueryItems(searchResults, ScoreChannel.DENSE);
    }

    @Override
    public List<EmbeddingModel.EmbeddingsQueryItem> hybridRetrieval(String queryText, float[] queryVector, int topK, String metricType, float denseWeight, float sparseWeight) {
        float safeDenseWeight = Math.max(denseWeight, 0f);
        float safeSparseWeight = Math.max(sparseWeight, 0f);
        if (safeDenseWeight <= 0f && safeSparseWeight <= 0f) {
            safeDenseWeight = 1f;
        }
        if (safeSparseWeight <= 0f) {
            return knnRetrieval(queryVector, topK);
        }
        if (safeDenseWeight <= 0f) {
            return sparseRetrieval(queryText, topK);
        }
        String denseMetricType = metricType;
        if (denseMetricType == null || denseMetricType.isBlank()) {
            denseMetricType = this.metricType;
        }
        int scoreTopK = Math.max(topK, 50);
        List<String> outputFields = List.of("hash", "embedding_model", "embedding_provider", "text", "text_chunk_id");
        List<JsonObject> denseResults = searchVectors(
                queryVector,
                "embedding",
                denseMetricType,
                scoreTopK,
                outputFields
        );
        List<JsonObject> sparseResults = searchVectors(
                queryText,
                "sparse",
                "BM25",
                scoreTopK,
                outputFields
        );
        Map<String, Float> denseScoreMap = extractScoreMap(denseResults);
        Map<String, Float> sparseScoreMap = extractScoreMap(sparseResults);
        Map<String, JsonObject> entityMap = mergeEntities(denseResults, sparseResults);
        List<EmbeddingModel.EmbeddingsQueryItem> merged = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : entityMap.entrySet()) {
            String hash = entry.getKey();
            Float denseScore = denseScoreMap.getOrDefault(hash, 0f);
            Float sparseScore = sparseScoreMap.getOrDefault(hash, 0f);
            float hybridScore = safeDenseWeight * denseScore + safeSparseWeight * sparseScore;
            JsonObject hit = entry.getValue();
            EmbeddingModel.EmbeddingsQueryItem item = new EmbeddingModel.EmbeddingsQueryItem()
                    .setHash(hash)
                    .setScore(hybridScore)
                    .setHybridScore(hybridScore)
                    .setDenseScore(denseScore)
                    .setSparseScore(sparseScore)
                    .setEmbeddingModel(getJsonString(hit, "embedding_model"))
                    .setEmbeddingProvider(getJsonString(hit, "embedding_provider"))
                    .setText(getJsonString(hit, "text"))
                    .setTextChunkId(getJsonString(hit, "text_chunk_id"));
            merged.add(item);
        }
        merged.sort(Comparator.comparing(EmbeddingModel.EmbeddingsQueryItem::getHybridScore, Comparator.nullsLast(Float::compareTo)).reversed());
        if (merged.size() > topK) {
            return new ArrayList<>(merged.subList(0, topK));
        }
        return merged;
    }

    private List<EmbeddingModel.EmbeddingsQueryItem> sparseRetrieval(String queryText, int topK) {
        List<JsonObject> searchResults = searchVectors(
                queryText,
                "sparse",
                "BM25",
                topK,
                List.of("hash", "embedding_model", "embedding_provider", "text", "text_chunk_id")
        );
        return toQueryItems(searchResults, ScoreChannel.SPARSE);
    }

    private List<JsonObject> searchVectors(Object vector,
                                           String annsField,
                                           String metricType,
                                           int topK,
                                           List<String> outputFields) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("collection_name", collectionName);
        payload.put("vector", vector);
        payload.put("top_k", topK);
        payload.put("output_fields", outputFields);
        if (annsField != null && !annsField.isBlank()) {
            payload.put("anns_field", annsField);
        }
        if (metricType != null && !metricType.isBlank()) {
            payload.put("search_params", Map.of("metric_type", metricType));
        }
        String jsonResponse = sendRequest("/vectors/search", payload);
        Type listType = new TypeToken<List<JsonObject>>() {
        }.getType();
        List<JsonObject> searchResults = gson.fromJson(jsonResponse, listType);
        return searchResults == null ? Collections.emptyList() : searchResults;
    }

    private List<EmbeddingModel.EmbeddingsQueryItem> toQueryItems(List<JsonObject> searchResults, ScoreChannel channel) {
        if (searchResults == null || searchResults.isEmpty()) {
            return Collections.emptyList();
        }
        List<EmbeddingModel.EmbeddingsQueryItem> resultItems = new ArrayList<>();
        for (JsonObject hit : searchResults) {
            Float score = getJsonFloat(hit, "score");
            EmbeddingModel.EmbeddingsQueryItem item = new EmbeddingModel.EmbeddingsQueryItem()
                    .setHash(getJsonString(hit, "hash"))
                    .setScore(score == null ? 0f : score)
                    .setEmbeddingModel(getJsonString(hit, "embedding_model"))
                    .setEmbeddingProvider(getJsonString(hit, "embedding_provider"))
                    .setText(getJsonString(hit, "text"))
                    .setTextChunkId(getJsonString(hit, "text_chunk_id"));
            if (score != null) {
                if (channel == ScoreChannel.DENSE) {
                    item.setDenseScore(score).setHybridScore(score);
                } else if (channel == ScoreChannel.SPARSE) {
                    item.setSparseScore(score).setHybridScore(score);
                } else {
                    item.setHybridScore(score);
                }
            }
            resultItems.add(item);
        }
        return resultItems;
    }

    private Map<String, Float> extractScoreMap(List<JsonObject> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Float> scoreMap = new HashMap<>();
        for (JsonObject hit : searchResults) {
            String hash = getJsonString(hit, "hash");
            Float score = getJsonFloat(hit, "score");
            if (hash != null && score != null) {
                scoreMap.put(hash, score);
            }
        }
        return scoreMap;
    }

    private Map<String, JsonObject> mergeEntities(List<JsonObject> denseResults, List<JsonObject> sparseResults) {
        Map<String, JsonObject> entityMap = new HashMap<>();
        if (denseResults != null) {
            for (JsonObject hit : denseResults) {
                String hash = getJsonString(hit, "hash");
                if (hash != null) {
                    entityMap.putIfAbsent(hash, hit);
                }
            }
        }
        if (sparseResults != null) {
            for (JsonObject hit : sparseResults) {
                String hash = getJsonString(hit, "hash");
                if (hash != null) {
                    entityMap.putIfAbsent(hash, hit);
                }
            }
        }
        return entityMap;
    }

    // 辅助方法：安全获取 String，防止字段不存在或为 null
    private String getJsonString(JsonObject json, String memberName) {
        return (json.has(memberName) && !json.get(memberName).isJsonNull())
                ? json.get(memberName).getAsString()
                : null;
    }

    private Float getJsonFloat(JsonObject json, String memberName) {
        return (json.has(memberName) && !json.get(memberName).isJsonNull())
                ? json.get(memberName).getAsFloat()
                : null;
    }

    @Override
    public void deleteData(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("collection_name", collectionName);
        payload.put("ids", ids);

        String response = sendRequest("/vectors/delete", payload);
        log.info("Delete response: {}", response);
    }

    private String sendRequest(String path, Object requestBody) {
        try {
            // 使用 Gson 进行序列化
            String jsonBody = gson.toJson(requestBody);

            return webClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(errorBody -> Mono.error(new RuntimeException("Milvus Python Service Error [" + response.statusCode() + "]: " + errorBody)))
                    )
                    .bodyToMono(String.class)
                    .block();

        } catch (Exception e) {
            log.error("Failed to connect to Milvus Python Service at {}", path, e);
            throw new RuntimeException("Vector DB Connection Failed", e);
        }
    }

    private enum ScoreChannel {
        DENSE,
        SPARSE,
        HYBRID
    }
}