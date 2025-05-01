package io.github.jerrt92.jrag.controller;

import io.github.jerrt92.jrag.model.ChatContextDto;
import io.github.jerrt92.jrag.model.ChatRequestDto;
import io.github.jerrt92.jrag.model.ChatResponseDto;
import io.github.jerrt92.jrag.model.ContextIdDto;
import io.github.jerrt92.jrag.model.HistoryContextList;
import io.github.jerrt92.jrag.model.MessageFeedbackRequest;
import io.github.jerrt92.jrag.model.Translator;
import io.github.jerrt92.jrag.model.security.SessionBo;
import io.github.jerrt92.jrag.server.api.ChatApi;
import io.github.jerrt92.jrag.service.llm.ChatContextBo;
import io.github.jerrt92.jrag.service.llm.ChatContextService;
import io.github.jerrt92.jrag.service.llm.ChatService;
import io.github.jerrt92.jrag.service.security.LoginService;
import io.github.jerrt92.jrag.utils.UUIDUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
public class ChatController implements ChatApi {
    private final ChatContextService chatContextService;
    private final ChatService chatService;
    private final LoginService loginService;

    public ChatController(ChatContextService chatContextService, ChatService chatService, LoginService loginService) {
        this.chatContextService = chatContextService;
        this.chatService = chatService;
        this.loginService = loginService;
    }

    @PostMapping("/v1/rest/jrag/chat")
    public SseEmitter chat(@RequestBody ChatRequestDto chatRequestDto) {
        SseEmitter sseEmitter = new SseEmitter(120000L);
        try {
            sseEmitter.send(new ChatResponseDto());
        } catch (IOException e) {
            sseEmitter.completeWithError(e);
        }
        SessionBo session = loginService.getSession();
        Thread chatThread = new Thread(() -> chatService.handleChat(sseEmitter, chatRequestDto, session == null ? null : session.getUserId()));
        Thread.startVirtualThread(chatThread);
        return sseEmitter;
    }

    @Override
    public ResponseEntity<ChatContextDto> getHistoryContext(String contextId) {
        ChatContextBo chatContextBo = chatContextService.getChatContext(contextId, null);
        return ResponseEntity.ok(chatContextBo == null ? null : Translator.translateToChatContextDto(chatContextBo));
    }

    @Override
    public ResponseEntity<Void> deleteHistoryContext(List<String> contextId) {
        chatContextService.deleteHistoryContext(contextId);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<HistoryContextList> getHistoryContextList(Integer offset, Integer limit) {
        return ResponseEntity.ok(chatContextService.getHistoryContextList(offset, limit));
    }

    @Override
    public ResponseEntity<ContextIdDto> getNewContextId() {
        return ResponseEntity.ok(
                new ContextIdDto().contextId(UUIDUtil.randomUUID())
        );
    }

    @Override
    public ResponseEntity<Void> addMessageFeedback(MessageFeedbackRequest messageFeedbackRequest) {
        chatContextService.addMessageFeedback(messageFeedbackRequest);
        return ResponseEntity.ok().build();
    }
}
