package io.github.jerrt92.jrag.service.llm;

import io.github.jerrt92.jrag.model.ChatRequestDto;
import io.github.jerrt92.jrag.model.MessageDto;
import io.github.jerrt92.jrag.model.Translator;
import io.github.jerrt92.jrag.service.llm.client.LlmClient;
import io.github.jerrt92.jrag.service.llm.tools.FunctionCallingService;
import io.github.jerrt92.jrag.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Service
public class ChatService {
    private final LlmClient llmClient;
    private final FunctionCallingService functionCallingService;
    private final ChatContextService chatContextService;
    private final ChatContextStorageService chatContextStorageService;

    public ChatService(LlmClient llmClient, FunctionCallingService functionCallingService, ChatContextService chatContextService, ChatContextStorageService chatContextStorageService) {
        this.llmClient = llmClient;
        this.functionCallingService = functionCallingService;
        this.chatContextService = chatContextService;
        this.chatContextStorageService = chatContextStorageService;
    }

    public void handleChat(SseEmitter sseEmitter, ChatRequestDto request, String userId) {
        try {
            String contextId = request.getContextId();
            if (contextId == null) {
                contextId = UUIDUtil.randomUUID();
            }
            // 从src/main/resources/system_prompt.txt中获取
            String systemPrompt = null;
            try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("system_prompt.txt")) {
                if (inputStream != null) {
                    systemPrompt = new String(inputStream.readAllBytes());
                }
            } catch (IOException e) {
                log.error("", e);
            }
            if (!CollectionUtils.isEmpty(request.getMessages())) {
                ChatContextBo chatContextBo = chatContextService.getChatContext(contextId, userId);
                if (chatContextBo == null) {
                    chatContextBo = new ChatContextBo(contextId, userId, llmClient, functionCallingService, chatContextStorageService);
                    chatContextBo.setMessages(Translator.translateToChatRequest(request).getMessages());
                    chatContextService.addChatContext(contextId, chatContextBo);
                }
                if (systemPrompt != null) {
                    MessageDto systemPromptMessageDto = new MessageDto();
                    systemPromptMessageDto.setRole(MessageDto.RoleEnum.SYSTEM);
                    systemPromptMessageDto.setContent(systemPrompt);
                    request.getMessages().add(request.getMessages().size() - 1, systemPromptMessageDto);
                }
                chatContextBo.chat(request, sseEmitter);
                log.info("问: " + request.getMessages().get(request.getMessages().size() - 1).getContent());
            }
        } catch (Throwable t) {
            sseEmitter.completeWithError(t);
        }
    }
}
