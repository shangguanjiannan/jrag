package io.github.jerrt92.jrag.service.llm;

import io.github.jerrt92.jrag.config.LlmProperties;
import io.github.jerrt92.jrag.model.ChatModel;
import io.github.jerrt92.jrag.model.ChatRequestDto;
import io.github.jerrt92.jrag.model.ChatResponseDto;
import io.github.jerrt92.jrag.model.FunctionCallingModel;
import io.github.jerrt92.jrag.model.Translator;
import io.github.jerrt92.jrag.service.llm.client.LlmClient;
import io.github.jerrt92.jrag.service.llm.tools.FunctionCallingService;
import io.github.jerrt92.jrag.service.llm.tools.ToolInterface;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * LLM对话上下文实例
 */
@Slf4j
public class ChatContextBo {
    @Getter
    private final String contextId;
    @Getter
    private final String userId;
    @Getter
    @Setter
    private List<ChatModel.Message> messages;

    private int index;

    private final LlmClient llmClient;

    private final FunctionCallingService functionCallingService;

    private Disposable eventStreamDisposable;

    private boolean isWaitingFunction = false;

    private List<FunctionCallingModel.Tool> tools;

    private ChatContextStorageService chatContextStorageService;

    private LlmProperties llmProperties;

    private ChatModel.ChatRequest lastRequest;

    private ChatModel.Message lastAssistantMassage = new ChatModel.Message()
            .setRole(ChatModel.Role.ASSISTANT)
            .setContent("");

    private ChatModel.Message lastFunctionCallingMassage = new ChatModel.Message()
            .setRole(ChatModel.Role.ASSISTANT)
            .setContent("");

    @Getter
    private Long lastRequestTime;

    private ConcurrentHashMap<Future, Future> functionCallingFutures = new ConcurrentHashMap<>();

    public ChatContextBo(String contextId, String userId, LlmClient llmClient, FunctionCallingService functionCallingService, ChatContextStorageService chatContextStorageService, LlmProperties llmProperties) {
        if (!CollectionUtils.isEmpty(functionCallingService.getTools())) {
            this.tools = new ArrayList<>();
            for (ToolInterface tool : functionCallingService.getTools().values()) {
                this.tools.add(tool.getToolInfo());
            }
        }
        this.contextId = contextId;
        this.userId = userId;
        this.llmClient = llmClient;
        this.functionCallingService = functionCallingService;
        this.chatContextStorageService = chatContextStorageService;
        this.llmProperties = llmProperties;
        lastRequestTime = System.currentTimeMillis();
    }

    public void chat(ChatRequestDto chatRequestDto, SseEmitter sseEmitter) {
        try {
            ChatModel.ChatRequest chatRequest = Translator.translateToChatRequest(chatRequestDto);
            messages = chatRequest.getMessages();
            index = chatRequest.getMessages().size();
            lastRequestTime = System.currentTimeMillis();
            if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
                // 如果存在未完成的对话，则忽略
                log.info("Event stream disposed");
            } else {
                List<ChatModel.Message> messagesContext = new ArrayList<>();
                for (ChatModel.Message wsMessage : chatRequest.getMessages()) {
                    ChatModel.Message message = new ChatModel.Message()
                            .setContent(wsMessage.getContent());
                    switch (wsMessage.getRole()) {
                        case SYSTEM:
                            message.setRole(ChatModel.Role.SYSTEM);
                            break;
                        case USER:
                            message.setRole(ChatModel.Role.USER);
                            break;
                        case ASSISTANT:
                            message.setRole(ChatModel.Role.ASSISTANT);
                            break;
                    }
                    messagesContext.add(message);
                }
                ChatModel.ChatRequest request = new ChatModel.ChatRequest()
                        .setMessages(messagesContext);
                lastRequest = request;
                if (llmProperties.useTools) {
                    request.setTools(tools);
                }
                eventStreamDisposable = llmClient.chat(request,
                        sseEmitter,
                        chatResponse -> consumeResponse(chatResponse, sseEmitter),
                        e -> onError(e, sseEmitter),
                        () -> onComplete(sseEmitter));
                sseEmitter.onCompletion(
                        () -> {
                            if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
                                eventStreamDisposable.dispose();
                            }
                        }
                );
                sseEmitter.onTimeout(
                        () -> {
                            if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
                                eventStreamDisposable.dispose();
                            }
                        }
                );
                sseEmitter.onError(
                        (e) -> {
                            if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
                                eventStreamDisposable.dispose();
                            }
                        }
                );
            }
        } catch (Throwable t) {
            log.error("", t);
            onError(t, sseEmitter);
        }
    }

    protected void toolCallResponse(Collection<FunctionCallingModel.ToolResponse> toolResponses, SseEmitter sseEmitter) {
        lastRequest.getMessages().add(lastFunctionCallingMassage);
        lastRequest.getMessages().add(FunctionCallingModel.buildToolResponseMessage(toolResponses));
        lastRequest.setTools(null);
        try {
            eventStreamDisposable = llmClient.chat(lastRequest,
                    sseEmitter,
                    chatResponse -> consumeResponse(chatResponse, sseEmitter),
                    e -> onError(e, sseEmitter),
                    () -> onComplete(sseEmitter));
        } catch (Throwable t) {
            log.error("", t);
            onError(t, sseEmitter);
        }
    }

    private void consumeResponse(ChatModel.ChatResponse response, SseEmitter sseEmitter) {
        try {
            if (Objects.nonNull(response.getMessage())) {
                if (!CollectionUtils.isEmpty(response.getMessage().getToolCalls())) {
                    // 模型有function calling请求
                    lastFunctionCallingMassage = response.getMessage();
                    for (ChatModel.ToolCall toolCall : response.getMessage().getToolCalls()) {
                        if (toolCall.getFunction() != null) {
                            try {
                                Future<String> stringFuture = functionCallingService.functionCalling(toolCall);
                                functionCallingFutures.put(stringFuture, stringFuture);
                                isWaitingFunction = true;
                                String result = stringFuture.get();
                                if (result != null) {
                                    toolCallResponse(Collections.singletonList(
                                            new FunctionCallingModel.ToolResponse()
                                                    .setName(toolCall.getFunction().getName())
                                                    .setResponseData(result)
                                    ), sseEmitter);
                                }
                                sseEmitter.onCompletion(
                                        () -> {
                                            stringFuture.cancel(true);
                                            if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
                                                eventStreamDisposable.dispose();
                                            }
                                        }
                                );
                                sseEmitter.onTimeout(
                                        () -> {
                                            stringFuture.cancel(true);
                                            if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
                                                eventStreamDisposable.dispose();
                                            }
                                        }
                                );
                                sseEmitter.onError(
                                        (e) -> {
                                            stringFuture.cancel(true);
                                            if (eventStreamDisposable != null && !eventStreamDisposable.isDisposed()) {
                                                eventStreamDisposable.dispose();
                                            }
                                        }
                                );
                            } catch (Exception e) {
                                log.error("Function calling error", e);
                            }
                        }
                    }
                    return;
                }
                if (response.getMessage().getRole().equals(ChatModel.Role.SYSTEM)) {
                    messages.add(response.getMessage());
                } else if (response.getMessage().getRole().equals(ChatModel.Role.ASSISTANT)) {
                    lastAssistantMassage.setContent(lastAssistantMassage.getContent() + response.getMessage().getContent());
                    if (!CollectionUtils.isEmpty(response.getMessage().getRagInfos())) {
                        lastAssistantMassage.setRagInfos(response.getMessage().getRagInfos());
                    }
                }
            }
            // 不发送系统Prompt给前端
            if (!response.getMessage().getRole().equals(ChatModel.Role.SYSTEM)) {
                ChatResponseDto chatResponseDto = Translator.translateToChatResponseDto(response, index);
                sseEmitter.send(chatResponseDto);
            }
            if (eventStreamDisposable.isDisposed()) {
                sseEmitter.complete();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            isWaitingFunction = false;
        }
    }

    private void onComplete(SseEmitter sseEmitter) {
        // 流结束
        log.info("回答" + ": " + this.lastAssistantMassage.getContent());
        this.messages.add(this.lastAssistantMassage);
        this.lastAssistantMassage = new ChatModel.Message()
                .setRole(ChatModel.Role.ASSISTANT)
                .setContent("");
        sseEmitter.complete();
        functionCallingFutures.forEach((future, future1) -> future.cancel(true));
        isWaitingFunction = false;
        chatContextStorageService.storageChatContextToDb(this);
    }

    private void onError(Throwable t, SseEmitter sseEmitter) {
        log.error("", t);
        functionCallingFutures.forEach((future, future1) -> future.cancel(true));
        isWaitingFunction = false;
        sseEmitter.completeWithError(t);
    }
}
