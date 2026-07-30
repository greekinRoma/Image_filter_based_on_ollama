package com.example.imagefilter.service;

import com.example.imagefilter.model.AppSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Central mutable configuration service.
 * <p>
 * Reads/writes {@code app_settings.json} in the working directory.
 * On first launch, seeds from {@code application.properties} defaults.
 * All settings changes are immediately persisted and survive restarts.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final Path SETTINGS_FILE = Path.of("app_settings.json");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile AppSettings settings;

    // Fallback defaults from application.properties (used on first launch)
    private final String fallbackImageDir;
    private final String fallbackModel;
    private final double fallbackTemperature;

    public SettingsService(
            @Value("${app.image-dir:./img}") String fallbackImageDir,
            @Value("${app.default-model:llava}") String fallbackModel,
            @Value("${app.default-temperature:0.1}") double fallbackTemperature) {
        this.fallbackImageDir = fallbackImageDir;
        this.fallbackModel = fallbackModel;
        this.fallbackTemperature = fallbackTemperature;
    }

    @PostConstruct
    public void init() {
        if (Files.exists(SETTINGS_FILE)) {
            try {
                settings = MAPPER.readValue(SETTINGS_FILE.toFile(), AppSettings.class);
                log.info("Loaded settings: imageDir={}, resultsDir={}",
                        settings.getImageDir(), settings.getResultsDir());
            } catch (IOException e) {
                log.warn("Failed to load settings, using defaults", e);
                settings = createDefaults();
                persist();
            }
        } else {
            settings = createDefaults();
            persist();
        }
    }

    // ── Accessors ──────────────────────────────────────────────────

    public String getImageDir() {
        lock.readLock().lock();
        try { return settings.getImageDir(); } finally { lock.readLock().unlock(); }
    }

    public String getResultsDir() {
        lock.readLock().lock();
        try { return settings.getResultsDir(); } finally { lock.readLock().unlock(); }
    }

    public String getDefaultModel() {
        lock.readLock().lock();
        try { return settings.getDefaultModel(); } finally { lock.readLock().unlock(); }
    }

    public double getDefaultTemperature() {
        lock.readLock().lock();
        try { return settings.getDefaultTemperature(); } finally { lock.readLock().unlock(); }
    }

    public AppSettings getSettings() {
        lock.readLock().lock();
        try {
            // Return a copy to prevent external mutation
            AppSettings copy = new AppSettings();
            copy.setImageDir(settings.getImageDir());
            copy.setResultsDir(settings.getResultsDir());
            copy.setDefaultModel(settings.getDefaultModel());
            copy.setDefaultTemperature(settings.getDefaultTemperature());
            return copy;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── Mutators ───────────────────────────────────────────────────

    /**
     * Apply partial updates from a map. Only non-null values are applied.
     * Validates that imageDir exists or can be created.
     */
    public void updateSettings(Map<String, Object> updates) {
        lock.writeLock().lock();
        try {
            boolean changed = false;

            if (updates.containsKey("imageDir")) {
                String dir = (String) updates.get("imageDir");
                if (dir != null && !dir.isBlank() && !dir.equals(settings.getImageDir())) {
                    Path p = Path.of(dir);
                    if (!Files.isDirectory(p)) {
                        throw new IllegalArgumentException("图片目录不存在: " + dir);
                    }
                    settings.setImageDir(dir);
                    changed = true;
                }
            }
            if (updates.containsKey("resultsDir")) {
                String dir = (String) updates.get("resultsDir");
                if (dir != null && !dir.isBlank() && !dir.equals(settings.getResultsDir())) {
                    Path p = Path.of(dir);
                    Files.createDirectories(p);
                    settings.setResultsDir(dir);
                    changed = true;
                }
            }
            if (updates.containsKey("defaultModel")) {
                String model = (String) updates.get("defaultModel");
                if (model != null && !model.isBlank()) {
                    settings.setDefaultModel(model);
                    changed = true;
                }
            }
            if (updates.containsKey("defaultTemperature")) {
                Object t = updates.get("defaultTemperature");
                double temp = t instanceof Number n ? n.doubleValue() : Double.parseDouble(t.toString());
                settings.setDefaultTemperature(temp);
                changed = true;
            }

            if (changed) {
                persist();
                log.info("Settings updated: {}", updates.keySet());
            }
        } catch (IOException e) {
            log.error("Failed to create results directory", e);
            throw new RuntimeException("无法创建结果目录: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Internal ───────────────────────────────────────────────────

    private AppSettings createDefaults() {
        AppSettings s = new AppSettings();
        s.setImageDir(fallbackImageDir);
        s.setResultsDir("filter_results");
        s.setDefaultModel(fallbackModel);
        s.setDefaultTemperature(fallbackTemperature);
        return s;
    }

    private void persist() {
        try {
            MAPPER.writeValue(SETTINGS_FILE.toFile(), settings);
            log.debug("Settings persisted to {}", SETTINGS_FILE);
        } catch (IOException e) {
            log.error("Failed to persist settings", e);
        }
    }
}
