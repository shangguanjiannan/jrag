package io.github.jerryt92.jrag.service.rag.vdb;

import io.github.jerryt92.jrag.model.EmbeddingModel;
import io.github.jerryt92.jrag.po.mgb.EmbeddingsItemPoWithBLOBs;

import java.util.List;

public interface VectorDatabaseService {
    void reBuildVectorDatabase(int dimension, String metricTypeStr);

    void initData(List<EmbeddingsItemPoWithBLOBs> embeddingsItemPos);

    /**
     * 根据余弦相似度近似近邻（ANN）搜索
     *
     * @param queryVector 查询向量
     * @param topK        表示返回最相似的K个向量
     */
    List<EmbeddingModel.EmbeddingsQueryItem> knnRetrieval(float[] queryVector, int topK);

    /**
     * 混合检索（语义向量 + 稀疏向量）
     *
     * @param queryText    查询文本
     * @param queryVector  查询向量
     * @param topK         表示返回最相似的K个向量
     * @param metricType   语义向量的度量方式
     * @param denseWeight  语义向量权重
     * @param sparseWeight 稀疏向量权重
     */
    List<EmbeddingModel.EmbeddingsQueryItem> hybridRetrieval(String queryText,
                                                             float[] queryVector,
                                                             int topK,
                                                             String metricType,
                                                             float denseWeight,
                                                             float sparseWeight);

    void putData(List<EmbeddingsItemPoWithBLOBs> embeddingsItems);

    void deleteData(List<String> ids);
}
