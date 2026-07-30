package com.example.imagefilter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Communicates with the Ollama REST API (default: http://localhost:11434).
 */
@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private static final String BASE_URL = "http://localhost:11434";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient = RestClient.builder()
            .baseUrl(BASE_URL)
            .build();

    /**
     * Test connection to Ollama and return status message with model count.
     */
    public String testConnection() {
        try {
            String body = restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(String.class);
            JsonNode root = MAPPER.readTree(body);
            int count = root.path("models").size();
            return "✅ Ollama 已连接，发现 " + count + " 个模型";
        } catch (Exception e) {
            log.error("Ollama connection failed", e);
            return "❌ 无法连接 Ollama: " + e.getMessage();
        }
    }

    /**
     * List available model names from Ollama.
     * Vision-capable models are prioritized.
     */
    public List<String> listModels() {
        try {
            String body = restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(String.class);
            JsonNode root = MAPPER.readTree(body);

            List<String> visionModels = new java.util.ArrayList<>();
            List<String> otherModels = new java.util.ArrayList<>();
            List<String> visionKeywords = List.of(
                    "llava", "vision", "bakllava", "minicpm", "gemma",
                    "llama3.2-vision", "phi3-v", "cogvlm", "fuyu",
                    "qwen2-vl", "pixtral", "llama");

            for (JsonNode model : root.path("models")) {
                String name = model.path("name").asText();
                boolean isVision = visionKeywords.stream()
                        .anyMatch(k -> name.toLowerCase().contains(k));
                if (isVision) {
                    visionModels.add(name);
                } else {
                    otherModels.add(name);
                }
            }
            visionModels.addAll(otherModels);
            return visionModels.isEmpty() ? List.of("llava (请先安装)") : visionModels;
        } catch (Exception e) {
            log.error("Failed to list models", e);
            return List.of("llava (请先安装)");
        }
    }

    /**
     * Send an image to Ollama for chat-based analysis.
     *
     * @param imagePath   path to the image file on disk
     * @param prompt      the text prompt
     * @param model       Ollama model name
     * @param temperature sampling temperature (0.0 - 1.0)
     * @return the model's text response
     */
    public String chatWithImage(String imagePath, String prompt,
                                String model, double temperature) throws IOException {
        byte[] imageBytes = Files.readAllBytes(Path.of(imagePath));
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "stream", false,
                "options", Map.of("temperature", temperature),
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", prompt,
                                "images", List.of(base64Image)
                        )
                )
        );

        String json = MAPPER.writeValueAsString(requestBody);
        log.debug("Sending to Ollama: model={}, prompt={}...", model,
                prompt.substring(0, Math.min(50, prompt.length())));

        String response = restClient.post()
                .uri("/api/chat")
                .header("Content-Type", "application/json")
                .body(json)
                .retrieve()
                .body(String.class);

        JsonNode root = MAPPER.readTree(response);
        return root.path("message").path("content").asText("").trim();
    }

    /**
     * Async version of {@link #chatWithImage} — runs on the ollamaExecutor
     * thread pool so Tomcat request threads are released immediately.
     */
    @Async("ollamaExecutor")
    public CompletableFuture<String> chatWithImageAsync(String imagePath, String prompt,
                                                         String model, double temperature) {
        try {
            String result = chatWithImage(imagePath, prompt, model, temperature);
            return CompletableFuture.completedFuture(result);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Classify the model's raw response into a short category label.
     * Replicates the Python classify_result() logic.
     */
    public String classifyResult(String response, String promptName) {
        if (response == null || response.isBlank()) return "UNKNOWN";
        String upper = response.toUpperCase().strip();

        // Quality check (prompt-specific)
        if (promptName != null && promptName.contains("Quality")) {
            if (upper.contains("GOOD")) return "GOOD";
            if (upper.contains("OK")) return "OK";
            if (upper.contains("BAD")) return "BAD";
        }

        // Document
        if (upper.contains("DOCUMENT") && !upper.contains("NOT_DOCUMENT")) return "DOCUMENT";
        if (upper.contains("NOT_DOCUMENT")) return "NOT_DOCUMENT";

        // Photo
        if (upper.contains("PHOTO") && !upper.contains("NOT_PHOTO")) return "PHOTO";
        if (upper.contains("NOT_PHOTO")) return "NOT_PHOTO";

        // Person
        if (upper.contains("HAS_PERSON")) return "HAS_PERSON";
        if (upper.contains("NO_PERSON")) return "NO_PERSON";

        // Brightness
        for (String level : List.of("BRIGHT", "NORMAL", "DARK")) {
            if (upper.contains(level)) return level;
        }

        // Color
        for (String ctype : List.of("COLORFUL", "MONOCHROME", "SEPIA")) {
            if (upper.contains(ctype)) return ctype;
        }

        // Screenshot
        if (upper.contains("SCREENSHOT") && !upper.contains("NOT_SCREENSHOT")) return "SCREENSHOT";
        if (upper.contains("NOT_SCREENSHOT")) return "NOT_SCREENSHOT";

        // Nature
        if (upper.contains("NATURE") && !upper.contains("NOT_NATURE")) return "NATURE";
        if (upper.contains("NOT_NATURE")) return "NOT_NATURE";

        // Fallback: first word
        String[] words = response.strip().split("\\s+");
        return words.length > 0 ? words[0] : "UNKNOWN";
    }
}
