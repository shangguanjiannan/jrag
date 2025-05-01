package io.github.jerrt92.jrag.service.llm.tools;


import io.github.jerrt92.jrag.model.FunctionCallingModel;

import java.util.Map;

public interface ToolInterface {
    FunctionCallingModel.Tool getToolInfo();

    String apply(Map<String, Object> request);
}
