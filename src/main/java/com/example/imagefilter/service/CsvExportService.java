package com.example.imagefilter.service;

import com.example.imagefilter.model.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exports analysis results to CSV files.
 * <p>
 * Supports both batch (collect-then-write) and streaming (row-by-row) modes.
 * Streaming mode avoids holding all results in memory — each row is flushed
 * to disk immediately.
 * <p>
 * The output directory is configurable via {@link SettingsService}.
 */
@Service
public class CsvExportService {

    private static final Logger log = LoggerFactory.getLogger(CsvExportService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String[] FIELDS = {"filename", "category", "response", "prompt", "model", "timestamp"};

    private final SettingsService settingsService;

    /** Track open writers keyed by file path (for streaming mode). */
    private final ConcurrentHashMap<Path, BufferedWriter> openWriters = new ConcurrentHashMap<>();

    public CsvExportService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Get the configured results directory, creating it if necessary.
     */
    private Path getResultsDir() throws IOException {
        Path dir = Path.of(settingsService.getResultsDir());
        Files.createDirectories(dir);
        return dir;
    }

    // ── Streaming mode (row-by-row, low memory) ──────────────────────

    /**
     * Create a new CSV file, write the header, and return its path.
     * The file stays open for {@link #appendRow(Path, AnalysisResult)} calls.
     * Call {@link #closeCsv(Path)} when done.
     */
    public Path beginCsv() throws IOException {
        Path resultsDir = getResultsDir();
        String timestamp = LocalDateTime.now().format(FMT);
        Path csvPath = resultsDir.resolve("filter_results_" + timestamp + ".csv");

        BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8);
        writer.write(csvEscape(FIELDS[0]));
        for (int i = 1; i < FIELDS.length; i++) {
            writer.write(',');
            writer.write(csvEscape(FIELDS[i]));
        }
        writer.write('\n');
        writer.flush();

        openWriters.put(csvPath, writer);
        log.info("CSV streaming started: {}", csvPath);
        return csvPath;
    }

    /**
     * Append a single result row to an open CSV file.
     * Flushes after each write so data is durable even if the process crashes.
     */
    public void appendRow(Path csvPath, AnalysisResult r) throws IOException {
        BufferedWriter writer = openWriters.get(csvPath);
        if (writer == null) {
            throw new IOException("No open CSV writer for: " + csvPath);
        }
        synchronized (writer) {
            writer.write(csvEscape(r.getFilename()));
            writer.write(',');
            writer.write(csvEscape(r.getCategory()));
            writer.write(',');
            writer.write(csvEscape(r.getResponse()));
            writer.write(',');
            writer.write(csvEscape(r.getPrompt()));
            writer.write(',');
            writer.write(csvEscape(r.getModel()));
            writer.write(',');
            writer.write(csvEscape(r.getTimestamp()));
            writer.write('\n');
            writer.flush();
        }
    }

    /**
     * Close the CSV writer. Must be called after all rows are written.
     */
    public void closeCsv(Path csvPath) {
        BufferedWriter writer = openWriters.remove(csvPath);
        if (writer != null) {
            try {
                writer.close();
                log.info("CSV streaming finished: {}", csvPath);
            } catch (IOException e) {
                log.error("Failed to close CSV writer: {}", csvPath, e);
            }
        }
    }

    // ── Batch mode (collect-then-write, backward compatible) ─────────

    /**
     * Save a list of results to CSV. Returns the path to the generated file.
     */
    public Path saveToCsv(List<AnalysisResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return null;
        }
        Path csvPath = beginCsv();
        for (AnalysisResult r : results) {
            appendRow(csvPath, r);
        }
        closeCsv(csvPath);
        log.info("CSV saved: {} ({} rows)", csvPath, results.size());
        return csvPath;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String csvEscape(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
