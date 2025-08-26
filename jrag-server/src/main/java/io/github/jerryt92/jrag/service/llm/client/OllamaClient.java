package io.github.jerryt92.jrag.service.llm.client;

import io.github.jerryt92.jrag.config.LlmProperties;
import io.github.jerryt92.jrag.model.ChatModel;
import io.github.jerryt92.jrag.model.FunctionCallingModel;
import io.github.jerryt92.jrag.model.ollama.OllamaModel;
import io.github.jerryt92.jrag.model.ollama.OllamaOptions;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
public class OllamaClient extends LlmClient {

    private final WebClient webClient;

    public OllamaClient(LlmProperties llmProperties) {
        super(llmProperties);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().protocol(HttpProtocol.HTTP11)))
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1024 * 1024, 1024 * 1024 * 50))))
                .build();
    }

    private final Set<SseEmitter> functionCallingSet = new HashSet<>();

    @Override
    public Disposable chat(ChatModel.ChatRequest chatRequest, SseEmitter sseEmitter, Consumer<ChatModel.ChatResponse> onResponse, Consumer<? super Throwable> onError, Runnable onComplete) {
        List<OllamaModel.Message> messagesContext = new ArrayList<>();
        for (ChatModel.Message chatMessage : chatRequest.getMessages()) {
            OllamaModel.Message ollamaMessage = new OllamaModel.Message()
                    .setContent(chatMessage.getContent());
            switch (chatMessage.getRole()) {
                case SYSTEM:
                    ollamaMessage.setRole(OllamaModel.Role.SYSTEM);
                    break;
                case USER:
                    ollamaMessage.setRole(OllamaModel.Role.USER);
                    break;
                case ASSISTANT:
                    ollamaMessage.setRole(OllamaModel.Role.ASSISTANT);
                    if (!CollectionUtils.isEmpty(chatMessage.getToolCalls())) {
                        // 工具调用信息
                        ollamaMessage.setToolCalls(new ArrayList<>());
                        for (int i = 0; i < chatMessage.getToolCalls().size(); i++) {
                            ChatModel.ToolCall toolCall = chatMessage.getToolCalls().get(i);
                            OllamaModel.ToolCall ollamaToolCall = new OllamaModel.ToolCall();
                            ollamaToolCall.setFunction(new OllamaModel.ToolCallFunction()
                                    .setName(toolCall.getFunction().getName())
                                    .setArguments(toolCall.getFunction().getArguments().getFirst()));
                            ollamaMessage.getToolCalls().add(ollamaToolCall);
                        }
                    }
                    break;
                case TOOL:
                    ollamaMessage.setRole(OllamaModel.Role.TOOL);
                    break;
            }
            messagesContext.add(ollamaMessage);
        }
        OllamaModel.ChatRequest request = new OllamaModel.ChatRequest()
                .setModel(llmProperties.ollamaModelName)
                .setKeepAlive(String.valueOf(llmProperties.ollamaKeepAliveSeconds))
                .setMessages(messagesContext)
                .setStream(true)
                .setOptions(
                        OllamaOptions.builder()
                                .numCtx(llmProperties.ollamaContextLength)
                                .temperature(llmProperties.temperature)
                                .build().toMap()
                );
        if (!CollectionUtils.isEmpty(chatRequest.getTools())) {
            List<OllamaModel.Tool> ollamaTools = new ArrayList<>();
            for (FunctionCallingModel.Tool tool : chatRequest.getTools()) {
                OllamaModel.Function function = new OllamaModel.Function()
                        .setName(tool.getName())
                        .setDescription(tool.getDescription());
                function.setParameters(FunctionCallingModel.generateToolParameters(tool.getParameters()));
                ollamaTools.add(new OllamaModel.Tool()
                        .setFunction(function)
                        .setType(OllamaModel.Type.FUNCTION)
                );
            }
            request.setTools(ollamaTools);
        }
        // Debug
//        log.info(org.springframework.ai.model.ModelOptionsUtils.toJsonString(request));
        Flux<OllamaModel.ChatResponse> eventStream = webClient.post()
                .uri(llmProperties.ollamaBaseUrl + "/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .accept(MediaType.ALL)
                .retrieve()
                .bodyToFlux(OllamaModel.ChatResponse.class)
                .doOnError(t -> {
                    functionCallingSet.remove(sseEmitter);
                    if (onError != null) {
                        onError.accept(t);
                    }
                }).doOnComplete(() -> {
                    if (!functionCallingSet.remove(sseEmitter)) {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                });
        return eventStream.subscribe(ollamaResponse -> this.consumeResponse(ollamaResponse, onResponse, sseEmitter));
    }

    private void consumeResponse(OllamaModel.ChatResponse ollamaResponse, Consumer<ChatModel.ChatResponse> onResponse, SseEmitter sseEmitter) {
        List<ChatModel.ToolCall> toolCalls = null;
        if (ollamaResponse == null) {
            sseEmitter.complete();
        }
        if (!CollectionUtils.isEmpty(ollamaResponse.getMessage().getToolCalls())) {
            // 模型有function calling请求
            functionCallingSet.add(sseEmitter);
            toolCalls = new ArrayList<>();
            for (OllamaModel.ToolCall ollamaToolCall : ollamaResponse.getMessage().getToolCalls()) {
                if (ollamaToolCall.getFunction() != null) {
                    ChatModel.ToolCall toolCall = new ChatModel.ToolCall()
                            .setFunction(
                                    new ChatModel.ToolCallFunction()
                                            .setName(ollamaToolCall.getFunction().getName())
                                            .setArguments(List.of(ollamaToolCall.getFunction().getArguments()))
                            );
                    toolCalls.add(toolCall);
                }
            }
        }
        if (ollamaResponse.getDone() && functionCallingSet.contains(sseEmitter)) {
            return;
        }
        ChatModel.ChatResponse chatResponse = new ChatModel.ChatResponse()
                .setMessage(
                        new ChatModel.Message()
                                .setRole(ChatModel.Role.ASSISTANT)
                                .setContent(ollamaResponse.getMessage().getContent())
                                .setToolCalls(toolCalls)
                )
                .setDone(ollamaResponse.getDone())
                .setDoneReason(ollamaResponse.getDoneReason());
        onResponse.accept(chatResponse);
    }
}
