package io.github.jerryt92.jrag.service.llm.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.jrag.config.LlmProperties;
import io.github.jerryt92.jrag.model.ChatCallback;
import io.github.jerryt92.jrag.model.ChatModel;
import io.github.jerryt92.jrag.model.FunctionCallingModel;
import io.github.jerryt92.jrag.model.openai.OpenAIModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenAiClient extends LlmClient {

    private final WebClient webClient;

    public OpenAiClient(LlmProperties llmProperties) {
        super(llmProperties);
        this.webClient = WebClient.builder().clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create().protocol(HttpProtocol.HTTP11)
                        )
                )
                .baseUrl(llmProperties.openAiBaseUrl)
                .defaultHeader("Authorization", "Bearer " + llmProperties.openAiKey)
                .build();
    }

    private final Map<String, ChatModel.ToolCallFunction> functionCallingInfoMap = new HashMap<>();

    @Override
    public Disposable chat(ChatModel.ChatRequest chatRequest, ChatCallback<ChatModel.ChatResponse> chatCallback) {
        List<OpenAIModel.ChatCompletionMessage> messagesContext = new ArrayList<>();
        for (ChatModel.Message chatMessage : chatRequest.getMessages()) {
            OpenAIModel.ChatCompletionMessage openAiMessage = new OpenAIModel.ChatCompletionMessage()
                    .setRawContent(chatMessage.getContent());
            switch (chatMessage.getRole()) {
                case SYSTEM:
                    openAiMessage.setRole(OpenAIModel.Role.SYSTEM);
                    break;
                case USER:
                    openAiMessage.setRole(OpenAIModel.Role.USER);
                    break;
                case ASSISTANT:
                    openAiMessage.setRole(OpenAIModel.Role.ASSISTANT);
                    if (!CollectionUtils.isEmpty(chatMessage.getToolCalls())) {
                        // 工具调用信息
                        openAiMessage.setToolCalls(new ArrayList<>());
                        for (int i = 0; i < chatMessage.getToolCalls().size(); i++) {
                            ChatModel.ToolCall toolCall = chatMessage.getToolCalls().get(i);
                            OpenAIModel.ToolCall openAiToolCall = new OpenAIModel.ToolCall();
                            openAiToolCall.setIndex(toolCall.getFunction().getIndex());
                            openAiToolCall.setId(toolCall.getFunction().getId());
                            if (ChatModel.Type.FUNCTION.equals(toolCall.getFunction().getType())) {
                                openAiToolCall.setType("function");
                            }
                            OpenAIModel.ChatCompletionFunction openAiFunction = new OpenAIModel.ChatCompletionFunction()
                                    .setName(toolCall.getFunction().getName())
                                    .setArguments(toolCall.getFunction().getArguments().toString());
                            openAiToolCall.setFunction(openAiFunction);
                            openAiMessage.getToolCalls().add(openAiToolCall);
                        }
                    }
                    break;
                case TOOL:
                    openAiMessage.setRole(OpenAIModel.Role.TOOL);
                    break;
            }
            messagesContext.add(openAiMessage);
        }
        OpenAIModel.ChatCompletionRequest request = new OpenAIModel.ChatCompletionRequest()
                .setMessages(messagesContext)
                .setModel(llmProperties.openAiModelName)
                .setStream(true)
                .setTemperature(llmProperties.temperature);
        if (!CollectionUtils.isEmpty(chatRequest.getTools())) {
            // 存在工具则传入
            List<OpenAIModel.FunctionTool> openAiTools = new ArrayList<>();
            for (FunctionCallingModel.Tool tool : chatRequest.getTools()) {
                OpenAIModel.FunctionTool functionTool = new OpenAIModel.FunctionTool();
                functionTool.setType(OpenAIModel.FunctionTool.Type.FUNCTION);
                OpenAIModel.FunctionTool.Function function = new OpenAIModel.FunctionTool.Function(
                        tool.getDescription(),
                        tool.getName(),
                        FunctionCallingModel.generateToolParameters(tool.getParameters()),
                        false
                );
                functionTool.setFunction(function);
                openAiTools.add(functionTool);
            }
            request.setTools(openAiTools);
        }
        // Debug
//        log.info(ModelOptionsUtils.toJsonString(request));
        log.info("context length:{}", ModelOptionsUtils.toJsonString(request).length());
        Flux<String> eventStream = webClient.post()
                .uri(llmProperties.completionsPath)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .accept(MediaType.ALL)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnError(t -> {
                    functionCallingInfoMap.remove(chatCallback.subscriptionId);
                    chatCallback.errorCall.accept(t);
                }).doOnComplete(() -> {
                    if (functionCallingInfoMap.remove(chatCallback.subscriptionId) == null) {
                        chatCallback.completeCall.run();
                    }
                });
        return eventStream.subscribe(chatCompletionChunk -> this.consumeResponse(chatCompletionChunk, chatCallback));
    }

    private void consumeResponse(String response, ChatCallback<ChatModel.ChatResponse> chatCallback) {
        try {
            ChatModel.ToolCallFunction toolCallFunction = functionCallingInfoMap.get(chatCallback.subscriptionId);
            if (response.trim().equals("[DONE]")) {
                if (toolCallFunction != null) {
                    // 1. 获取原始的参数流字符串
                    String rawArgs = toolCallFunction.getArgumentsStream().toString().trim();
                    String finalArgumentsString;

                    // =========================================================
                    // 2. 预处理和修复非标准 JSON 格式
                    // =========================================================
                    try {
                        rawArgs = fixNonStandardJson(rawArgs); // 调用新的修复方法
                    } catch (Exception fixE) {
                        // 如果修复过程本身出现严重问题，记录警告并使用原字符串
                        log.error("Failed to fix non-standard JSON arguments. Using raw arguments.", fixE);
                    }

                    // 3. 修复并行调用中可能出现的 }{ 连接问题 (保留原逻辑，但现在处理的是修复后的字符串)
                    rawArgs = rawArgs.replace("}{", "},{");

                    // =========================================================
                    // 4. 健壮性解析 (与上一回复的逻辑一致，但现在处理的是更干净的 rawArgs)
                    // =========================================================
                    try {
                        // 尝试解析为 JSONArray
                        JSONArray argArray = JSONArray.parseArray(rawArgs);
                        finalArgumentsString = argArray.toJSONString();

                    } catch (Exception e1) {
                        // 尝试解析为 JSONObject
                        try {
                            JSONObject argObject = JSONObject.parseObject(rawArgs);
                            finalArgumentsString = "[" + argObject.toJSONString() + "]";

                        } catch (Exception e2) {
                            // 最终回退
                            log.warn("Function calling arguments parsing failed (Array and Object). Falling back to basic array wrapping. Raw: {}", rawArgs, e2);
                            if (rawArgs.startsWith("[") || rawArgs.startsWith("{")) {
                                finalArgumentsString = rawArgs;
                            } else {
                                finalArgumentsString = "[" + rawArgs + "]";
                            }
                        }
                    }

                    // 5. 最终解析
                    List<JSONObject> argumentJsons = JSONArray.parseArray(finalArgumentsString, JSONObject.class);
                    List<Map<String, Object>> argumentMaps = new ArrayList<>(argumentJsons);
                    toolCallFunction.setArguments(argumentMaps);
                    // 6. 构建并发送最终的 ChatResponse
                    ChatModel.ToolCall toolCall = new ChatModel.ToolCall()
                            .setFunction(toolCallFunction);
                    ChatModel.ChatResponse chatResponse = new ChatModel.ChatResponse()
                            .setMessage(
                                    new ChatModel.Message()
                                            .setRole(ChatModel.Role.ASSISTANT)
                                            .setContent("")
                                            .setToolCalls(List.of(toolCall))
                            )
                            .setDone(true);
                    chatCallback.responseCall.accept(chatResponse);
                } else {
                    // 非 Function Calling，内容已输出完毕
                    ChatModel.ChatResponse chatResponse = new ChatModel.ChatResponse()
                            .setMessage(
                                    new ChatModel.Message()
                                            .setRole(ChatModel.Role.ASSISTANT)
                                            .setContent("")
                            )
                            .setDone(true);
                    chatCallback.responseCall.accept(chatResponse);
                }
            } else {
                // ============== 处理非 [DONE] 的流式数据块 ==============
                OpenAIModel.ChatCompletionChunk chatCompletionChunk = ModelOptionsUtils.jsonToObject(response, OpenAIModel.ChatCompletionChunk.class);
                List<ChatModel.ToolCall> toolCalls = null;
                StringBuilder content = new StringBuilder();
                OpenAIModel.ChatCompletionFinishReason finishReason = null;
                if (chatCompletionChunk.getChoices() != null) {
                    for (OpenAIModel.ChatCompletionChunk.ChunkChoice chunkChoice : chatCompletionChunk.getChoices()) {

                        if (!CollectionUtils.isEmpty(chunkChoice.getDelta().getToolCalls())) {
                            // 模型有 function calling 请求
                            List<OpenAIModel.ToolCall> openAiToolCalls = chunkChoice.getDelta().getToolCalls();
                            // 初始化或获取 toolCallFunction
                            if (toolCallFunction == null) {
                                toolCallFunction = new ChatModel.ToolCallFunction();
                                toolCallFunction.setArgumentsStream(new StringBuilder());
                                functionCallingInfoMap.put(chatCallback.subscriptionId, toolCallFunction);
                            }
                            for (OpenAIModel.ToolCall openAiToolCall : openAiToolCalls) {
                                if (StringUtils.isNotBlank(openAiToolCall.getFunction().getName())) {
                                    toolCallFunction.setName(openAiToolCall.getFunction().getName());
                                }
                                if (openAiToolCall.getFunction().getArguments() != null) {
                                    // 仅仅进行字符串拼接，不在此处解析
                                    toolCallFunction.getArgumentsStream().append(openAiToolCall.getFunction().getArguments());
                                }
                                if (openAiToolCall.getIndex() != null) {
                                    toolCallFunction.setIndex(openAiToolCall.getIndex());
                                }
                                if (openAiToolCall.getId() != null) {
                                    toolCallFunction.setId(openAiToolCall.getId());
                                }
                            }
                        } else {
                            // 模型返回文本内容
                            if (chunkChoice.getDelta().getRawContent() != null) {
                                content.append(chunkChoice.getDelta().getRawContent());
                            }
                            if (chunkChoice.getFinishReason() != null) {
                                finishReason = chunkChoice.getFinishReason();
                            }
                            // 发送当前内容的流式响应
                            ChatModel.ChatResponse chatResponse = new ChatModel.ChatResponse()
                                    .setMessage(
                                            new ChatModel.Message()
                                                    .setRole(ChatModel.Role.ASSISTANT)
                                                    .setContent(content.toString())
                                                    .setToolCalls(toolCalls)
                                    )
                                    .setDone(false)
                                    .setDoneReason(finishReason == null ? null : finishReason.toString());
                            chatCallback.responseCall.accept(chatResponse);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log.error("Error processing chat completion chunk.", t);
            chatCallback.errorCall.accept(t);
        }
    }

    /**
     * 尝试将非标准的 JSON 字符串（如键没有引号，使用等号分隔）修复为标准 JSON 格式。
     * 修复目标格式：{expression=314 * 60 + 45} -> {"expression": "314 * 60 + 45"}
     */
    private String fixNonStandardJson(String rawJson) {
        if (StringUtils.isBlank(rawJson)) {
            return rawJson;
        }
        String result = rawJson.trim();
        // 1. 移除最外层的可能的多余方括号或大括号，只保留一个层次的 JSON 结构
        // 假设我们处理的是单个对象或一个对象数组
        // 示例：[[{...}]] 变为 [{...}]
        while ((result.startsWith("[") && result.endsWith("]")) || (result.startsWith("{") && result.endsWith("}"))) {
            String stripped = result.substring(1, result.length() - 1).trim();
            if (stripped.startsWith("{") || stripped.startsWith("[")) {
                result = stripped;
            } else {
                break;
            }
        }
        // 2. 检查并修复非标准键值对
        // 匹配: {key=value} -> {"key": "value"}
        // 正则表达式: 匹配 { 或 , 之后跟着一个或多个非空格字符作为键，然后是 =，
        //             再跟着任意字符直到下一个 , 或 }。
        // 注意：这里的正则修复是基于最简单的非标准格式假设，可能需要根据实际遇到的情况调整。
        if (result.startsWith("{") && result.endsWith("}")) {
            // 匹配未被引号包裹的键名（非空白字符），后面跟一个等号
            result = result.replaceAll("([,{])\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*", "$1\"$2\":");
            // 匹配值（如果值没有被引号包裹且包含非数字字符，则需要包裹）
            // 这里的修复非常复杂，因为值可能包含空格和特殊字符。
            // 鉴于您的例子是表达式 `314 * 60 + 45`，它应该被视为字符串。
            // 最简单粗暴的方式是：如果发现 `:` 后面没有引号，则给值加上引号。
            // 由于 Fastjson2 允许在一定条件下不使用引号包裹值（如数字），但您的值是一个表达式，
            // 针对 `key: value` 结构，如果 value 没有被引号包裹且不是数字，则包裹它。
            // 考虑到 LLM 返回的参数通常是字符串或数字，并且表达式通常是字符串，
            // 简单假设所有非数字值都需要被包裹。
            // 这是一个高风险操作，我们退而求其次，只修复键名和等号。
        }
        // 3. 最终尝试使用 Fastjson2 的宽松模式（如果支持）或简单的正则修正
        // 针对原始问题中的 [{expression=314 * 60 + 45}]，它不是标准的 JSON 对象，
        // 最有效的方法是确保键名被引号包裹，并且等号被替换为冒号。
        // 重新运行一次更保守的修复，确保键名被引号，等号被冒号替换
        if (result.startsWith("{") && result.endsWith("}")) {
            // 修复键名: key=value -> "key"=value
            result = result.replaceAll("([,{])\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=", "$1\"$2\"=");
            // 修复等号: "key"=value -> "key":value
            result = result.replace('=', ':');
            // 修复值：如果值不是数字且未被引号包裹（这很难用正则准确实现）
            // 对于 `expression: 314 * 60 + 45`，我们假定 `314 * 60 + 45` 是一个字符串，需要引号
            // 这是一个临时的、针对性的修复，将表达式值包裹起来：
            if (result.contains("\"expression\":") && !result.contains("\"expression\":\"")) {
                result = result.replaceAll("\"expression\":\\s*(.+?)([},])", "\"expression\": \"$1\"$2");
            }
        }
        return result;
    }
}
