package com.example.imagefilter.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Holds the result of analyzing a single image with Ollama.
 */
public class AnalysisResult {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String filename;
    private String prompt;
    private String model;
    private String response;
    private String category;
    private String timestamp;
    private String elapsed;

    public AnalysisResult() {}

    public AnalysisResult(String filename, String prompt, String model,
                          String response, String category, String elapsed) {
        this.filename = filename;
        this.prompt = prompt.length() > 200 ? prompt.substring(0, 200) : prompt;
        this.model = model;
        this.response = response;
        this.category = category.length() > 50 ? category.substring(0, 50) : category;
        this.timestamp = LocalDateTime.now().format(FMT);
        this.elapsed = elapsed;
    }

    // ── Getters / Setters ──

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getElapsed() { return elapsed; }
    public void setElapsed(String elapsed) { this.elapsed = elapsed; }
}
