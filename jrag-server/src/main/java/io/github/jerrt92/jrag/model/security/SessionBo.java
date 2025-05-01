package io.github.jerrt92.jrag.model.security;

import lombok.Data;

@Data
public class SessionBo {
    private String sessionId;
    private String userId;
    private String username;
    private long expireTime;
}
