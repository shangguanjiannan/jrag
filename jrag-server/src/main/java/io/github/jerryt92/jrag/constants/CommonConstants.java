package io.github.jerryt92.jrag.constants;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class CommonConstants {
    @Getter
    private static String springApplicationName;

    @Value("${spring.application.name}")
    private void setSpringApplicationName(String springApplicationName) {
        CommonConstants.springApplicationName = springApplicationName;
    }

    public static final String FILE_URL = "/v1/rest/jrag/file/";

    public static final String STATIC_FILE_URL = FILE_URL + "static/";

    public static final String ZH_CN = "zh_CN";

    public static final String EN_US = "en_US";

    public static final String ENGLISH_LLM_PROMPT = "All answers are in English.";
}
