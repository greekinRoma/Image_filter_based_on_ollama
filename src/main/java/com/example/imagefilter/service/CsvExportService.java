package com.example.imagefilter.service;

import com.example.imagefilter.model.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports analysis results to CSV files.
 */
@Service
public class CsvExportService {

    private static final Logger log = LoggerFactory.getLogger(CsvExportService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String[] FIELDS = {"filename", "category", "response", "prompt", "model", "timestamp"};

    private final Path resultsDir;

    public CsvExportService() {
        // Default: ./filter_results relative to working directory
        this.resultsDir = Path.of("filter_results");
        try {
            Files.createDirectories(resultsDir);
        } catch (IOException e) {
            log.error("Failed to create results directory", e);
        }
    }

    /**
     * Save a list of results to CSV. Returns the path to the generated file.
     */
    public Path saveToCsv(List<AnalysisResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return null;
        }
        String timestamp = LocalDateTime.now().format(FMT);
        Path csvPath = resultsDir.resolve("filter_results_" + timestamp + ".csv");

        StringBuilder sb = new StringBuilder();
        // Header
        sb.append(csvEscape(FIELDS[0]));
        for (int i = 1; i < FIELDS.length; i++) {
            sb.append(',').append(csvEscape(FIELDS[i]));
        }
        sb.append('\n');

        // Rows
        for (AnalysisResult r : results) {
            sb.append(csvEscape(r.getFilename()));
            sb.append(',').append(csvEscape(r.getCategory()));
            sb.append(',').append(csvEscape(r.getResponse()));
            sb.append(',').append(csvEscape(r.getPrompt()));
            sb.append(',').append(csvEscape(r.getModel()));
            sb.append(',').append(csvEscape(r.getTimestamp()));
            sb.append('\n');
        }

        Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
        log.info("CSV saved: {} ({} rows)", csvPath, results.size());
        return csvPath;
    }

    private static String csvEscape(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
