package com.example.imagefilter.service;

import com.example.imagefilter.model.AnalysisResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory state for analysis results.
 * Replicates the Python AppState class.
 */
@Service
public class StateService {

    private final Map<String, AnalysisResult> results = new ConcurrentHashMap<>();

    public void setResult(String filename, AnalysisResult result) {
        results.put(filename, result);
    }

    public Optional<AnalysisResult> getResult(String filename) {
        return Optional.ofNullable(results.get(filename));
    }

    public Map<String, AnalysisResult> getAllResults() {
        return new LinkedHashMap<>(results);
    }

    public void clearResults() {
        results.clear();
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
}
