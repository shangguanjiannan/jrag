INSERT INTO user (id, username, password_hash, create_time, role)
VALUES ('a9e4a18b43ce44f2aece312667099a99', 'admin', '81279c81fe7d776409c3f1f3259b25ad3c055d3b78dc0fa29b463d7d73607c86',
        null, 1);

INSERT INTO ai_properties (property_name, property_value, description)
VALUES ('RETRIEVE_TOP_K', '5', '检索结果最大数量');

INSERT INTO ai_properties (property_name, property_value, description)
VALUES ('RETRIEVE_METRIC_TYPE', 'COSINE', '检索度量指标（COSINE余弦相似度、IP积内积、L2欧式距离等）');

INSERT INTO ai_properties (property_name, property_value, description)
VALUES ('RETRIEVE_METRIC_SCORE_COMPARE_EXPR', '> 0.7',
        '检索度量结果评分过滤条件，不同度量指标的取值范围不同，请根据实际情况修改');