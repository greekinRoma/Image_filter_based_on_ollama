package com.example.imagefilter.service;

import com.example.imagefilter.model.AnalysisResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Thread-safe in-memory state for analysis results.
 * <p>
 * Results are persisted to {@code analysis_cache.json} in the results directory
 * so they survive application restarts. Saves are debounced (2 s after last change)
 * to avoid excessive I/O during batch processing.
 */
@Service
public class StateService {

    private static final Logger log = LoggerFactory.getLogger(StateService.class);
    private static final String CACHE_FILENAME = "analysis_cache.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, AnalysisResult> results = new ConcurrentHashMap<>();
    private final SettingsService settingsService;
    private final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "state-saver");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingSave = null;

    public StateService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    private Path getCacheFile() throws IOException {
        Path dir = Path.of(settingsService.getResultsDir());
        Files.createDirectories(dir);
        return dir.resolve(CACHE_FILENAME);
    }

    @PostConstruct
    public void loadCache() {
        try {
            Path file = getCacheFile();
            if (!Files.exists(file)) {
                log.info("No analysis cache found, starting fresh");
                return;
            }
            Map<String, AnalysisResult> loaded = MAPPER.readValue(
                    file.toFile(), new TypeReference<Map<String, AnalysisResult>>() {});
            results.putAll(loaded);
            log.info("Loaded {} cached analysis results from {}", loaded.size(), file);
        } catch (IOException e) {
            log.warn("Failed to load analysis cache, starting with empty state", e);
        }
    }

    @PreDestroy
    public void saveCacheSync() {
        // Force immediate save on shutdown — cancel any pending debounced save
        if (pendingSave != null) {
            pendingSave.cancel(false);
        }
        doSave();
        saveScheduler.shutdown();
    }

    // ── State operations ────────────────────────────────────────────

    public void setResult(String filename, AnalysisResult result) {
        results.put(filename, result);
        scheduleSave();
    }

    public Optional<AnalysisResult> getResult(String filename) {
        return Optional.ofNullable(results.get(filename));
    }

    public Map<String, AnalysisResult> getAllResults() {
        return new LinkedHashMap<>(results);
    }

    public void clearResults() {
        results.clear();
        scheduleSave();
    }

    /**
     * Returns statistics: total count and category breakdown.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", results.size());

        Map<String, Integer> categories = new LinkedHashMap<>();
        for (AnalysisResult r : results.values()) {
            String cat = r.getCategory() != null ? r.getCategory() : "unknown";
            categories.merge(cat, 1, Integer::sum);
        }
        stats.put("categories", categories);
        return stats;
    }

    /**
     * Returns a sorted list of unique categories from all results.
     */
    public List<String> getCategories() {
        Set<String> cats = new TreeSet<>();
        cats.add("全部");
        for (AnalysisResult r : results.values()) {
            if (r.getCategory() != null && !r.getCategory().isEmpty()) {
                cats.add(r.getCategory());
            }
        }
        return new ArrayList<>(cats);
    }

    /**
     * Filter results by category. Pass null or "全部" to get all.
     */
    public List<AnalysisResult> filterResults(String categoryFilter) {
        List<AnalysisResult> all = new ArrayList<>(results.values());
        if (categoryFilter == null || categoryFilter.isEmpty() || "全部".equals(categoryFilter)) {
            return all;
        }
        return all.stream()
                .filter(r -> categoryFilter.equals(r.getCategory()))
                .toList();
    }

    // ── Debounced persistence ───────────────────────────────────────

    private void scheduleSave() {
        synchronized (this) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = saveScheduler.schedule(this::doSave, 2, TimeUnit.SECONDS);
        }
    }

    private void doSave() {
        try {
            Path file = getCacheFile();
            // Take a snapshot of current results to avoid holding locks during I/O
            Map<String, AnalysisResult> snapshot = new LinkedHashMap<>(results);
            MAPPER.writeValue(file.toFile(), snapshot);
            log.debug("Saved {} results to cache", snapshot.size());
        } catch (IOException e) {
            log.error("Failed to save analysis cache", e);
        }
    }
}
