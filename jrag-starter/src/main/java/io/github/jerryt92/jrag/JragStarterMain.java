package io.github.jerryt92.jrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// --spring.config.location=classpath:/application.yaml,classpath:/application-sqlite.yaml
@SpringBootApplication
public class JragStarterMain {
    public static void main(String[] args) {
        applyExternalConfigLocation();
        ConfigurableApplicationContext context = SpringApplication.run(JragStarterMain.class, args);
        printStartupInfo(context);
    }

    private static void applyExternalConfigLocation() {
        String externalConfigPath = System.getenv("JRAG_CONFIG_PATH");
        if (externalConfigPath == null || externalConfigPath.isBlank()) {
            return;
        }

        String normalized = externalConfigPath.trim();
        if (!normalized.startsWith("file:")) {
            Path path = Paths.get(normalized).toAbsolutePath();
            String pathString = path.toString();
            boolean looksLikeFile = pathString.endsWith(".yml") || pathString.endsWith(".yaml");
            boolean isDirectory = Files.isDirectory(path);
            if (!pathString.endsWith("/") && (isDirectory || !looksLikeFile)) {
                pathString = pathString + "/";
            }
            normalized = "file:" + pathString;
        }

        String existing = System.getProperty("spring.config.additional-location");
        if (existing == null || existing.isBlank()) {
            System.setProperty("spring.config.additional-location", normalized);
        } else if (!existing.contains(normalized)) {
            System.setProperty("spring.config.additional-location", existing + "," + normalized);
        }
    }

    private static void printStartupInfo(ConfigurableApplicationContext context) {
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String host = "localhost";
        String[] profiles = env.getActiveProfiles();
        String profileInfo = profiles.length > 0 ? String.join(",", profiles) : "default";
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🎉 Application started successfully!");
        System.out.println("🌐 Application URL: http://" + host + ":" + port + contextPath);
        System.out.println("📁 Profile(s): " + profileInfo);
        System.out.println("⏰ Started at: " + java.time.LocalDateTime.now());
        System.out.println("=".repeat(60));
    }
}