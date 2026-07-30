package com.example.imagefilter.model;

/**
 * Mutable application settings persisted to app_settings.json.
 * Holds runtime-configurable paths and defaults that survive restarts.
 */
public class AppSettings {

    private String imageDir = "./img";
    private String resultsDir = "filter_results";
    private String defaultModel = "llava";
    private double defaultTemperature = 0.1;

    public AppSettings() {}

    // ── Getters / Setters ──

    public String getImageDir() { return imageDir; }
    public void setImageDir(String imageDir) { this.imageDir = imageDir; }

    public String getResultsDir() { return resultsDir; }
    public void setResultsDir(String resultsDir) { this.resultsDir = resultsDir; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public double getDefaultTemperature() { return defaultTemperature; }
    public void setDefaultTemperature(double defaultTemperature) { this.defaultTemperature = defaultTemperature; }
}
