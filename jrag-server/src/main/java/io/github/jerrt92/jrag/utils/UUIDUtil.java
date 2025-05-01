package io.github.jerrt92.jrag.utils;

public final class UUIDUtil {
    public static String randomUUID() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
