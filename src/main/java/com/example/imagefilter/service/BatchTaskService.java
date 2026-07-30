package com.example.imagefilter.service;

import com.example.imagefilter.model.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages background batch-processing tasks with progress tracking.
 * <p>
 * Supports optional frame-deduplication: when enabled, compares each image
 * against a sliding window of the last N "qualified" frames using Ollama
 * vision comparison, skipping near-duplicates automatically.
 */
@Service
public class BatchTaskService {

    private static final Logger log = LoggerFactory.getLogger(BatchTaskService.class);
    private static final int PARALLEL_WORKERS = 4;

    private final ConcurrentHashMap<String, BatchTask> tasks = new ConcurrentHashMap<>();
    private final OllamaService ollamaService;
    private final ImageService imageService;
    private final StateService stateService;
    private final CsvExportService csvExportService;

    /** Limit concurrent Ollama calls so the GPU / Ollama server is not overwhelmed. */
    private final Semaphore ollamaSemaphore = new Semaphore(2);

    public BatchTaskService(OllamaService ollamaService,
                            ImageService imageService,
                            StateService stateService,
                            CsvExportService csvExportService) {
        this.ollamaService = ollamaService;
        this.imageService = imageService;
        this.stateService = stateService;
        this.csvExportService = csvExportService;
    }

    /**
     * Create a new batch task and start processing in the background.
     */
    public String startBatch(String prompt, String model, double temperature,
                             String imageDir, int maxImages,
                             boolean dedupEnabled, int dedupWindowSize) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        BatchTask task = new BatchTask(taskId);
        tasks.put(taskId, task);

        executeBatch(taskId, prompt, model, temperature, imageDir, maxImages,
                dedupEnabled, dedupWindowSize);

        return taskId;
    }

    @Async("batchExecutor")
    public void executeBatch(String taskId, String prompt, String model,
                             double temperature, String imageDir, int maxImages,
                             boolean dedupEnabled, int dedupWindowSize) {
        BatchTask task = tasks.get(taskId);
        if (task == null) return;

        Path csvPath = null;
        ExecutorService workerPool = null;

        try {
            List<String> allFiles = imageService.getImageFiles(imageDir);
            if (allFiles.isEmpty()) {
                task.status = BatchStatus.DONE;
                task.summary = "❌ 未找到图片";
                return;
            }

            List<String> batchFiles = (maxImages > 0 && maxImages < allFiles.size())
                    ? new ArrayList<>(allFiles.subList(0, maxImages))
                    : new ArrayList<>(allFiles);

            task.total = batchFiles.size();

            // Open CSV for streaming row-by-row writes
            csvPath = csvExportService.beginCsv();
            task.csvUrl = "/api/download/" + csvPath.getFileName().toString();

            AtomicInteger completedCounter = new AtomicInteger(0);
            ConcurrentHashMap<String, Long> catCounts = new ConcurrentHashMap<>();
            AtomicInteger skippedCounter = new AtomicInteger(0);
            AtomicInteger dedupCounter = new AtomicInteger(0);
            Path imageDirPath = Path.of(imageDir);

            if (dedupEnabled) {
                // ── Sequential mode with sliding-window dedup ──────────
                processSequentialWithDedup(task, batchFiles, imageDirPath,
                        prompt, model, temperature, csvPath,
                        completedCounter, catCounts, skippedCounter, dedupCounter,
                        dedupWindowSize);
            } else {
                // ── Parallel mode (no dedup) ───────────────────────────
                workerPool = Executors.newFixedThreadPool(PARALLEL_WORKERS, r -> {
                    Thread t = new Thread(r, "batch-worker-" + taskId);
                    t.setDaemon(true);
                    return t;
                });

                final Path finalCsvPath = csvPath;
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (String filename : batchFiles) {
                    futures.add(CompletableFuture.runAsync(() ->
                            processOneImage(filename, imageDirPath, prompt, model, temperature,
                                    finalCsvPath, completedCounter, catCounts, skippedCounter, task),
                            workerPool));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            // Close the CSV
            csvExportService.closeCsv(csvPath);

            // Build summary
            task.summary = buildSummary(task.total, skippedCounter.get(),
                    dedupCounter.get(), catCounts, csvPath.getFileName().toString());
            task.status = BatchStatus.DONE;

        } catch (Exception e) {
            log.error("Batch task {} failed", taskId, e);
            task.status = BatchStatus.FAILED;
            task.summary = "❌ 批处理失败: " + e.getMessage();
            if (csvPath != null) {
                csvExportService.closeCsv(csvPath);
            }
        } finally {
            if (workerPool != null) {
                workerPool.shutdown();
                try { workerPool.awaitTermination(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    // ── Sequential dedup processing ──────────────────────────────────

    private void processSequentialWithDedup(
            BatchTask task, List<String> batchFiles, Path imageDirPath,
            String prompt, String model, double temperature, Path csvPath,
            AtomicInteger completedCounter, ConcurrentHashMap<String, Long> catCounts,
            AtomicInteger skippedCounter, AtomicInteger dedupCounter,
            int windowSize) {

        // Sliding window of the last N qualified (non-duplicate) frame file paths
        ArrayDeque<String> qualifiedWindow = new ArrayDeque<>(windowSize);

        for (int i = 0; i < batchFiles.size(); i++) {
            String filename = batchFiles.get(i);
            Path filePath = imageDirPath.resolve(filename);
            if (!Files.exists(filePath)) {
                task.completed = completedCounter.incrementAndGet();
                continue;
            }

            // Check cache first
            Optional<AnalysisResult> existing = stateService.getResult(filename);
            if (existing.isPresent() && prompt.equals(existing.get().getPrompt())) {
                AnalysisResult r = existing.get();
                writeToCsvSafe(csvPath, r);
                catCounts.merge(r.getCategory() != null ? r.getCategory() : "?", 1L, Long::sum);
                skippedCounter.incrementAndGet();
                // Cached results that aren't duplicates also qualify for the window
                if (!"DUPLICATE".equals(r.getCategory())) {
                    addToWindow(qualifiedWindow, filePath, windowSize);
                }
                task.completed = completedCounter.incrementAndGet();
                continue;
            }

            // Dedup check: compare against each qualified frame in the window
            boolean isDuplicate = false;
            for (String refPath : qualifiedWindow) {
                try {
                    if (ollamaService.compareImages(filePath.toString(), refPath, model)) {
                        isDuplicate = true;
                        break;
                    }
                } catch (IOException e) {
                    log.warn("Dedup comparison failed for {} vs {}: {}",
                            filename, Path.of(refPath).getFileName(), e.getMessage());
                    // On comparison error, assume NOT similar (fail open)
                }
            }

            if (isDuplicate) {
                // Write a placeholder result for the duplicate
                AnalysisResult dupResult = new AnalysisResult(
                        filename, prompt, model, "[SKIPPED - duplicate frame]",
                        "DUPLICATE", null);
                stateService.setResult(filename, dupResult);
                writeToCsvSafe(csvPath, dupResult);
                catCounts.merge("DUPLICATE", 1L, Long::sum);
                dedupCounter.incrementAndGet();
                task.completed = completedCounter.incrementAndGet();
                continue;
            }

            // Not a duplicate — run the main analysis
            processOneImage(filename, imageDirPath, prompt, model, temperature,
                    csvPath, completedCounter, catCounts, skippedCounter, task);

            // Add this qualified frame to the sliding window
            addToWindow(qualifiedWindow, filePath, windowSize);
        }
    }

    private void addToWindow(ArrayDeque<String> window, Path filePath, int maxSize) {
        window.addLast(filePath.toString());
        while (window.size() > maxSize) {
            window.removeFirst();
        }
    }

    // ── Single image processing (used by both modes) ─────────────────

    private void processOneImage(
            String filename, Path imageDirPath, String prompt, String model,
            double temperature, Path csvPath, AtomicInteger completedCounter,
            ConcurrentHashMap<String, Long> catCounts, AtomicInteger skippedCounter,
            BatchTask task) {

        Path filePath = imageDirPath.resolve(filename);
        if (!Files.exists(filePath)) {
            task.completed = completedCounter.incrementAndGet();
            return;
        }

        // Check cache first (fast path)
        Optional<AnalysisResult> existing = stateService.getResult(filename);
        if (existing.isPresent() && prompt.equals(existing.get().getPrompt())) {
            AnalysisResult r = existing.get();
            writeToCsvSafe(csvPath, r);
            catCounts.merge(r.getCategory() != null ? r.getCategory() : "?", 1L, Long::sum);
            skippedCounter.incrementAndGet();
            task.completed = completedCounter.incrementAndGet();
            return;
        }

        // Phase 1: Read file
        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Batch: failed to read {}", filename, e);
            task.completed = completedCounter.incrementAndGet();
            return;
        }

        // Phase 2: Ollama inference (semaphore-gated)
        try {
            ollamaSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.completed = completedCounter.incrementAndGet();
            return;
        }

        try {
            String response = ollamaService.chatWithBytes(
                    imageBytes, prompt, model, temperature);
            String category = ollamaService.classifyResult(response, "");
            if (category.length() > 50) category = category.substring(0, 50);

            AnalysisResult result = new AnalysisResult(
                    filename, prompt, model, response, category, null);
            stateService.setResult(filename, result);

            writeToCsvSafe(csvPath, result);
            catCounts.merge(category != null ? category : "?", 1L, Long::sum);
        } catch (IOException e) {
            log.error("Batch: Ollama call failed for {}", filename, e);
        } finally {
            ollamaSemaphore.release();
        }

        task.completed = completedCounter.incrementAndGet();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void writeToCsvSafe(Path csvPath, AnalysisResult r) {
        try {
            csvExportService.appendRow(csvPath, r);
        } catch (IOException e) {
            log.error("Failed to write CSV row for {}", r.getFilename(), e);
        }
    }

    private String buildSummary(int total, int skipped, int dedup,
                                ConcurrentHashMap<String, Long> catCounts,
                                String csvFileName) {
        StringBuilder summary = new StringBuilder();
        summary.append("✅ 批处理完成！共 ").append(total).append(" 张图片");
        if (skipped > 0 || dedup > 0) {
            summary.append("（");
            if (skipped > 0) summary.append(skipped).append(" 张缓存");
            if (skipped > 0 && dedup > 0) summary.append("，");
            if (dedup > 0) summary.append(dedup).append(" 张去重跳过");
            summary.append("）");
        }
        summary.append("\n\n**分类统计:**\n");
        catCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> summary.append("- `").append(e.getKey())
                        .append("`: ").append(e.getValue()).append(" 张\n"));
        summary.append("\n📁 结果已保存: `").append(csvFileName).append("`");
        return summary.toString();
    }

    // ── Public API ───────────────────────────────────────────────────

    public BatchTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public void cleanupOldTasks() {
        long doneCount = tasks.values().stream()
                .filter(t -> t.status == BatchStatus.DONE || t.status == BatchStatus.FAILED)
                .count();
        if (doneCount > 50) {
            tasks.values().removeIf(t ->
                    t.status == BatchStatus.DONE || t.status == BatchStatus.FAILED);
        }
    }

    // ── Inner classes ─────────────────────────────────────────────────

    public enum BatchStatus {
        RUNNING, DONE, FAILED
    }

    public static class BatchTask {
        public String taskId;
        public BatchStatus status = BatchStatus.RUNNING;
        public int total;
        public volatile int completed;
        public String summary;
        public String csvUrl;

        BatchTask(String taskId) {
            this.taskId = taskId;
        }
    }
}
