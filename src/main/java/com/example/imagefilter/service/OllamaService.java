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
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Communicates with the Ollama REST API (default: http://localhost:11434).
 * <p>
 * Includes an LRU cache for base64-encoded images to avoid re-reading
 * and re-encoding the same file on repeated analysis with different prompts.
 */
@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private static final String BASE_URL = "http://localhost:11434";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CACHE_ENTRIES = 50;

    private final RestClient restClient = RestClient.builder()
            .baseUrl(BASE_URL)
            .build();

    /**
     * Thread-safe LRU cache for base64-encoded images.
     * Key: imagePath + "::" + lastModifiedTime
     */
    private final Map<String, String> imageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

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
        String base64Image = getOrEncodeImage(imagePath);
        return chatWithBase64(base64Image, prompt, model, temperature);
    }

    /**
     * Send pre-encoded image bytes to Ollama. Skips the file-read + encode step.
     * Useful in batch processing where the file has already been read.
     */
    public String chatWithBytes(byte[] imageBytes, String prompt,
                                String model, double temperature) throws IOException {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        return chatWithBase64(base64Image, prompt, model, temperature);
    }

    /**
     * Core chat logic — sends base64-encoded image to Ollama.
     */
    private String chatWithBase64(String base64Image, String prompt,
                                   String model, double temperature) throws IOException {
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
     * Get base64-encoded image from LRU cache, or read + encode + cache it.
     */
    private String getOrEncodeImage(String imagePath) throws IOException {
        Path filePath = Path.of(imagePath);
        FileTime mtime = Files.getLastModifiedTime(filePath);
        String cacheKey = imagePath + "::" + mtime.toMillis();

        String cached = imageCache.get(cacheKey);
        if (cached != null) {
            log.debug("Image cache hit: {}", imagePath);
            return cached;
        }

        log.debug("Image cache miss, encoding: {}", imagePath);
        byte[] imageBytes = Files.readAllBytes(filePath);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        imageCache.put(cacheKey, base64Image);
        return base64Image;
    }

    /**
     * Compare two images using Ollama vision to determine if they are
     * nearly identical / very similar scenes.
     *
     * @return true if the images are SIMILAR (near-duplicate)
     */
    public boolean compareImages(String imagePath1, String imagePath2,
                                 String model) throws IOException {
        String b64_1 = getOrEncodeImage(imagePath1);
        String b64_2 = getOrEncodeImage(imagePath2);

        String prompt = "Are these two images nearly identical or showing very similar scenes? "
                + "Consider composition, objects, lighting, and camera angle. "
                + "Answer ONLY with one word: SIMILAR or DIFFERENT.";

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "stream", false,
                "options", Map.of("temperature", 0.0),
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", prompt,
                                "images", List.of(b64_1, b64_2)
                        )
                )
        );

        String json = MAPPER.writeValueAsString(requestBody);
        String response = restClient.post()
                .uri("/api/chat")
                .header("Content-Type", "application/json")
                .body(json)
                .retrieve()
                .body(String.class);

        JsonNode root = MAPPER.readTree(response);
        String answer = root.path("message").path("content").asText("").trim().toUpperCase();
        log.debug("Image comparison result: {}", answer);
        return answer.contains("SIMILAR") && !answer.contains("DIFFERENT");
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
     */
    public String classifyResult(String response, String promptName) {
        if (response == null || response.isBlank()) return "UNKNOWN";
        String upper = response.toUpperCase().strip();

        if (promptName != null && promptName.contains("Quality")) {
            if (upper.contains("GOOD")) return "GOOD";
            if (upper.contains("OK")) return "OK";
            if (upper.contains("BAD")) return "BAD";
        }

        if (upper.contains("DOCUMENT") && !upper.contains("NOT_DOCUMENT")) return "DOCUMENT";
        if (upper.contains("NOT_DOCUMENT")) return "NOT_DOCUMENT";

        if (upper.contains("PHOTO") && !upper.contains("NOT_PHOTO")) return "PHOTO";
        if (upper.contains("NOT_PHOTO")) return "NOT_PHOTO";

        if (upper.contains("HAS_PERSON")) return "HAS_PERSON";
        if (upper.contains("NO_PERSON")) return "NO_PERSON";

        for (String level : List.of("BRIGHT", "NORMAL", "DARK")) {
            if (upper.contains(level)) return level;
        }

        for (String ctype : List.of("COLORFUL", "MONOCHROME", "SEPIA")) {
            if (upper.contains(ctype)) return ctype;
        }

        if (upper.contains("SCREENSHOT") && !upper.contains("NOT_SCREENSHOT")) return "SCREENSHOT";
        if (upper.contains("NOT_SCREENSHOT")) return "NOT_SCREENSHOT";

        if (upper.contains("NATURE") && !upper.contains("NOT_NATURE")) return "NATURE";
        if (upper.contains("NOT_NATURE")) return "NOT_NATURE";

        String[] words = response.strip().split("\\s+");
        return words.length > 0 ? words[0] : "UNKNOWN";
    }
}
