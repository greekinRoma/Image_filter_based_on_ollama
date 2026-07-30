package com.example.imagefilter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists user's custom prompts to a JSON file so they survive restarts.
 * <p>
 * The history file is stored in the configurable results directory
 * (via {@link SettingsService}), so it follows the user's chosen output path.
 */
@Service
public class PromptHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PromptHistoryService.class);
    private static final String HISTORY_FILENAME = "prompt_history.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final SettingsService settingsService;

    public PromptHistoryService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    private Path getHistoryFile() throws IOException {
        Path dir = Path.of(settingsService.getResultsDir());
        Files.createDirectories(dir);
        return dir.resolve(HISTORY_FILENAME);
    }

    /**
     * Load the saved prompt history.
     * @return a map with keys "singlePrompt" and "batchPrompt", or empty map if no history exists
     */
    public Map<String, String> loadHistory() {
        try {
            Path file = getHistoryFile();
            if (!Files.exists(file)) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, String> history = MAPPER.readValue(file.toFile(), LinkedHashMap.class);
            log.debug("Loaded prompt history: {}", history.keySet());
            return history;
        } catch (IOException e) {
            log.warn("Failed to load prompt history, using defaults", e);
            return Map.of();
        }
    }

    /**
     * Save the prompt history. Merges with existing entries so partial updates work.
     */
    public void saveHistory(Map<String, String> updates) {
        try {
            Path file = getHistoryFile();

            // Load existing, then merge updates
            Map<String, String> history = new LinkedHashMap<>();
            if (Files.exists(file)) {
                @SuppressWarnings("unchecked")
                Map<String, String> existing = MAPPER.readValue(file.toFile(), LinkedHashMap.class);
                history.putAll(existing);
            }
            history.putAll(updates);

            MAPPER.writeValue(file.toFile(), history);
            log.debug("Saved prompt history: {}", history.keySet());
        } catch (IOException e) {
            log.error("Failed to save prompt history", e);
        }
    }
}
