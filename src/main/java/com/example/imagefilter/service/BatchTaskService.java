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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Manages background batch-processing tasks with progress tracking.
 * <p>
 * Each batch runs on the {@code batchExecutor} thread pool so Tomcat
 * threads are freed immediately. Clients poll {@code /api/batch/status}
 * for progress updates.
 */
@Service
public class BatchTaskService {

    private static final Logger log = LoggerFactory.getLogger(BatchTaskService.class);

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
     *
     * @return taskId for polling progress via {@link #getTask(String)}
     */
    public String startBatch(String prompt, String model, double temperature,
                             String imageDir, int maxImages) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        BatchTask task = new BatchTask(taskId);
        tasks.put(taskId, task);

        // Fire-and-forget: the batch runs on the batchExecutor thread pool
        executeBatch(taskId, prompt, model, temperature, imageDir, maxImages);

        return taskId;
    }

    @Async("batchExecutor")
    public void executeBatch(String taskId, String prompt, String model,
                             double temperature, String imageDir, int maxImages) {
        BatchTask task = tasks.get(taskId);
        if (task == null) return;

        try {
            List<String> allFiles = imageService.getImageFiles(imageDir);
            if (allFiles.isEmpty()) {
                task.status = BatchStatus.DONE;
                task.summary = "❌ 未找到图片";
                return;
            }

            List<String> batchFiles = (maxImages > 0 && maxImages < allFiles.size())
                    ? allFiles.subList(0, maxImages)
                    : allFiles;

            task.total = batchFiles.size();
            List<AnalysisResult> resultsList = new ArrayList<>();
            int skipped = 0;

            for (int i = 0; i < batchFiles.size(); i++) {
                String filename = batchFiles.get(i);
                Path path = Path.of(imageDir).resolve(filename);
                if (!Files.exists(path)) continue;

                // Check cache
                Optional<AnalysisResult> existing = stateService.getResult(filename);
                if (existing.isPresent()
                        && prompt.equals(existing.get().getPrompt())) {
                    resultsList.add(existing.get());
                    skipped++;
                    task.completed = i + 1;
                    continue;
                }

                // Acquire semaphore before calling Ollama
                try {
                    ollamaSemaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                try {
                    String response = ollamaService.chatWithImage(
                            path.toString(), prompt, model, temperature);
                    String category = ollamaService.classifyResult(response, "");
                    if (category.length() > 50) category = category.substring(0, 50);

                    AnalysisResult result = new AnalysisResult(
                            filename, prompt, model, response, category, null);
                    stateService.setResult(filename, result);
                    resultsList.add(result);
                } catch (IOException e) {
                    log.error("Batch: failed for {}", filename, e);
                } finally {
                    ollamaSemaphore.release();
                }

                task.completed = i + 1;
            }

            // Build summary
            Map<String, Long> catCounts = new LinkedHashMap<>();
            for (AnalysisResult r : resultsList) {
                catCounts.merge(r.getCategory() != null ? r.getCategory() : "?", 1L, Long::sum);
            }

            StringBuilder summary = new StringBuilder();
            summary.append("✅ 批处理完成！共 ").append(task.total).append(" 张图片");
            if (skipped > 0) {
                summary.append("（其中 ").append(skipped).append(" 张从缓存读取）");
            }
            summary.append("\n\n**分类统计:**\n");
            catCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> summary.append("- `").append(e.getKey())
                            .append("`: ").append(e.getValue()).append(" 张\n"));

            // Export CSV
            try {
                Path csvPath = csvExportService.saveToCsv(resultsList);
                if (csvPath != null) {
                    task.csvUrl = "/api/download/" + csvPath.getFileName().toString();
                    summary.append("\n📁 结果已保存: `").append(csvPath.getFileName()).append("`");
                }
            } catch (IOException e) {
                log.error("Failed to export CSV", e);
            }

            task.summary = summary.toString();
            task.status = BatchStatus.DONE;

        } catch (Exception e) {
            log.error("Batch task {} failed", taskId, e);
            task.status = BatchStatus.FAILED;
            task.summary = "❌ 批处理失败: " + e.getMessage();
        }
    }

    /**
     * Get the current state of a batch task.
     */
    public BatchTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Clean up old completed tasks (keep last 50).
     */
    public void cleanupOldTasks() {
        long doneCount = tasks.values().stream()
                .filter(t -> t.status == BatchStatus.DONE || t.status == BatchStatus.FAILED)
                .count();
        if (doneCount > 50) {
            tasks.values().removeIf(t ->
                    t.status == BatchStatus.DONE || t.status == BatchStatus.FAILED);
            // Simple approach: if too many, clear all completed
            // A more sophisticated approach would use a LRU eviction
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
        public int completed;
        public String summary;
        public String csvUrl;

        BatchTask(String taskId) {
            this.taskId = taskId;
        }
    }
}
