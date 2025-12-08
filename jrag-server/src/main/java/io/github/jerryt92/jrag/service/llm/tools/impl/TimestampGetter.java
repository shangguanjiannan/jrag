package io.github.jerryt92.jrag.service.llm.tools.impl;

import io.github.jerryt92.jrag.service.llm.tools.ToolInterface;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TimestampGetter extends ToolInterface {

    public TimestampGetter() {
        toolInfo.setName("timestamp_getter")
                .setDescription("获取当前毫秒时间戳")
                .setParameters(
                        Collections.emptyList()
                );
    }

    @Override
    public List<String> apply(List<Map<String, Object>> requests) {
        List<String> results = new ArrayList<>();
        results.add(getCurrentLocalTime());
        return results;
    }

    private String getCurrentLocalTime() {
        return String.valueOf(System.currentTimeMillis());
    }
}