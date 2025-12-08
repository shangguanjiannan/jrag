package io.github.jerryt92.jrag.service.llm.tools.impl;

import io.github.jerryt92.jrag.model.FunctionCallingModel;
import io.github.jerryt92.jrag.service.llm.tools.ToolInterface;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TimestampReadabilityTranslator extends ToolInterface {
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public TimestampReadabilityTranslator() {
        toolInfo.setName("timestamp_readability_translator")
                .setDescription("将毫秒时间戳转换为YYYY-MM-DD HH:mm:ss格式的时间")
                .setParameters(
                        Collections.singletonList(
                                new FunctionCallingModel.Tool.Parameter()
                                        .setName("timestamp")
                                        .setDescription("毫秒时间戳")
                                        .setType("number")
                                        .setRequired(true)
                        )
                );
    }

    @Override
    public List<String> apply(List<Map<String, Object>> requests) {
        List<String> results = new ArrayList<>();
        for (Map<String, Object> request : requests) {
            long timestamp = (long) request.get("timestamp");
            String date = dateFormat.format(timestamp);
            results.add(date);
        }
        return results;
    }
}