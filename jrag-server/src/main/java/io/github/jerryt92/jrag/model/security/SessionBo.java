package io.github.jerryt92.jrag.model.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Data;

@Data
public class SessionBo {
    private String sessionId;
    private String userId;
    private String username;
    private RoleEnum role;
    private long expireTime;

    public enum RoleEnum {
        USER(0),

        ADMIN(1);

        private final Integer value;

        RoleEnum(Integer value) {
            this.value = value;
        }

        @JsonValue
        public Integer getValue() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }

        @JsonCreator
        public static RoleEnum fromValue(Integer value) {
            for (RoleEnum b : RoleEnum.values()) {
                if (b.value.equals(value)) {
                    return b;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }
    }
}
