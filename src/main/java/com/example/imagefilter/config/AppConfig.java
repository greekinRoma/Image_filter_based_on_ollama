package com.example.imagefilter.config;

import com.example.imagefilter.service.SettingsService;
import org.springframework.context.annotation.Configuration;

/**
 * Application configuration — delegates to {@link SettingsService}
 * for all runtime-mutable values while providing backward-compatible
 * getters for existing consumers.
 */
@Configuration
public class AppConfig {

    private final SettingsService settings;

    public AppConfig(SettingsService settings) {
        this.settings = settings;
    }

    public String getImageDir() {
        return settings.getImageDir();
    }

    public String getDefaultModel() {
        return settings.getDefaultModel();
    }

    public double getDefaultTemperature() {
        return settings.getDefaultTemperature();
    }
}
