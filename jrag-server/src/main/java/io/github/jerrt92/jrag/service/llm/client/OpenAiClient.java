package io.github.jerrt92.jrag.service.llm.client;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerrt92.jrag.config.LlmProperties;
import io.github.jerrt92.jrag.model.ChatModel;
import io.github.jerrt92.jrag.model.FunctionCallingModel;
import io.github.jerrt92.jrag.model.ModelOptionsUtils;
import io.github.jerrt92.jrag.model.openai.OpenAIModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

    private Map<SseEmitter, FunctionCallingInfo> functionCallingInfoMap = new HashMap<>();

    private static class FunctionCallingInfo {
        public String functionName;
        public StringBuilder functionArguments;
        public Integer toolCallIndex;
        public String toolCallId;
    }

    @Override
    public Disposable chat(ChatModel.ChatRequest chatRequest, SseEmitter sseEmitter, Consumer<ChatModel.ChatResponse> onResponse, Consumer<? super Throwable> onError, Runnable onComplete) {
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
        if (!CollectionUtils.isEmpty(chatRequest.getTools()) && llmProperties.openAiUseTools) {
            // 存在工具则传入
            // TODO: 工具调用待实现
        }
        Flux<String> eventStream = webClient.post()
                .uri(llmProperties.completionsPath)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .accept(MediaType.ALL)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnError(t -> {
                    functionCallingInfoMap.remove(sseEmitter);
                    if (onError != null) {
                        onError.accept(t);
                    }
                }).doOnComplete(() -> {
                    if (functionCallingInfoMap.remove(sseEmitter) == null) {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                });
        return eventStream.subscribe(chatCompletionChunk -> this.consumeResponse(chatCompletionChunk, onResponse, sseEmitter, onError));
    }

    private void consumeResponse(String response, Consumer<ChatModel.ChatResponse> onResponse, SseEmitter sseEmitter, Consumer<? super Throwable> onError) {
        try {
            FunctionCallingInfo functionCallingInfo = functionCallingInfoMap.get(sseEmitter);
            if (response.trim().equals("[DONE]")) {
                if (functionCallingInfo != null) {
                    // Function calling输出完成
                    ChatModel.ToolCall toolCall = new ChatModel.ToolCall()
                            .setFunction(new ChatModel.ToolCallFunction()
                                    .setName(functionCallingInfo.functionName)
                                    .setArguments(JSONObject.parseObject(functionCallingInfo.functionArguments.toString()))
                            );
                    ChatModel.ChatResponse chatResponse = new ChatModel.ChatResponse()
                            .setMessage(
                                    new ChatModel.Message()
                                            .setRole(ChatModel.Role.ASSISTANT)
                                            .setContent("")
                                            .setToolCalls(List.of(toolCall))
                            )
                            .setDone(true);
                    onResponse.accept(chatResponse);
                } else {
                    ChatModel.ChatResponse chatResponse = new ChatModel.ChatResponse()
                            .setMessage(
                                    new ChatModel.Message()
                                            .setRole(ChatModel.Role.ASSISTANT)
                                            .setContent("")
                            )
                            .setDone(true);
                    onResponse.accept(chatResponse);
                }
            } else {
                OpenAIModel.ChatCompletionChunk chatCompletionChunk = ModelOptionsUtils.jsonToObject(response, OpenAIModel.ChatCompletionChunk.class);
                List<ChatModel.ToolCall> toolCalls = null;
                StringBuilder content = new StringBuilder();
                OpenAIModel.ChatCompletionFinishReason finishReason = null;
                if (chatCompletionChunk.getChoices() != null) {
                    for (OpenAIModel.ChatCompletionChunk.ChunkChoice chunkChoice : chatCompletionChunk.getChoices()) {
                        if (!CollectionUtils.isEmpty(chunkChoice.getDelta().getToolCalls())) {
                            // 模型有function calling请求
                            List<OpenAIModel.ToolCall> openAiToolCalls = chunkChoice.getDelta().getToolCalls();
                            if (functionCallingInfo == null) {
                                functionCallingInfo = new FunctionCallingInfo();
                                functionCallingInfo.functionArguments = new StringBuilder();
                                functionCallingInfoMap.put(sseEmitter, functionCallingInfo);
                            }
                            for (OpenAIModel.ToolCall openAiToolCall : openAiToolCalls) {
                                if (openAiToolCall.getFunction().getName() != null) {
                                    functionCallingInfo.functionName = openAiToolCall.getFunction().getName();
                                }
                                if (openAiToolCall.getFunction().getArguments() != null) {
                                    functionCallingInfo.functionArguments.append(openAiToolCall.getFunction().getArguments());
                                }
                                if (openAiToolCall.getIndex() != null) {
                                    functionCallingInfo.toolCallIndex = openAiToolCall.getIndex();
                                }
                                if (openAiToolCall.getId() != null) {
                                    functionCallingInfo.toolCallId = openAiToolCall.getId();
                                }
                            }
                        } else {
                            if (chunkChoice.getDelta().getRawContent() != null) {
                                content.append(chunkChoice.getDelta().getRawContent());
                            }
                            if (chunkChoice.getFinishReason() != null) {
                                finishReason = chunkChoice.getFinishReason();
                            }
                            ChatModel.ChatResponse chatResponse = new ChatModel.ChatResponse()
                                    .setMessage(
                                            new ChatModel.Message()
                                                    .setRole(ChatModel.Role.ASSISTANT)
                                                    .setContent(content.toString())
                                                    .setToolCalls(toolCalls)
                                    )
                                    .setDone(false)
                                    .setDoneReason(finishReason == null ? null : finishReason.toString());
                            onResponse.accept(chatResponse);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log.error("", t);
            onError.accept(t);
        }
    }
}
