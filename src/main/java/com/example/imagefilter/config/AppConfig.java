package com.example.imagefilter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Application configuration — image directory and other settings.
 */
@Configuration
public class AppConfig {

    @Value("${app.image-dir:./img}")
    private String imageDir;

    @Value("${app.default-model:llava}")
    private String defaultModel;

    @Value("${app.default-temperature:0.1}")
    private double defaultTemperature;

    public String getImageDir() {
        return imageDir;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public double getDefaultTemperature() {
        return defaultTemperature;
    }
}
