package com.example.imagefilter.controller;

import com.example.imagefilter.config.AppConfig;
import com.example.imagefilter.model.AnalysisResult;
import com.example.imagefilter.model.PredefinedPrompt;
import com.example.imagefilter.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Controller
public class FilterController {

    private static final Logger log = LoggerFactory.getLogger(FilterController.class);

    private final AppConfig config;
    private final StateService state;
    private final OllamaService ollama;
    private final ImageService imageService;
    private final CsvExportService csvExport;
    private final BatchTaskService batchTaskService;

    public FilterController(AppConfig config, StateService state,
                            OllamaService ollama, ImageService imageService,
                            CsvExportService csvExport,
                            BatchTaskService batchTaskService) {
        this.config = config;
        this.state = state;
        this.ollama = ollama;
        this.imageService = imageService;
        this.csvExport = csvExport;
        this.batchTaskService = batchTaskService;
    }

    // ─────────────────────────────────────────────
    // Page
    // ─────────────────────────────────────────────

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("imgDir", config.getImageDir());
        model.addAttribute("defaultModel", config.getDefaultModel());
        model.addAttribute("defaultTemperature", config.getDefaultTemperature());
        model.addAttribute("predefinedPrompts", PredefinedPrompt.getAll());
        model.addAttribute("models", ollama.listModels());
        model.addAttribute("imageFiles", imageService.getImageFiles(config.getImageDir()));
        return "index";
    }

    // ─────────────────────────────────────────────
    // Image serving
    // ─────────────────────────────────────────────

    @GetMapping("/api/images/{filename}")
    @ResponseBody
    public ResponseEntity<Resource> serveImage(@PathVariable String filename) {
        Path path = Path.of(config.getImageDir()).resolve(filename).normalize();
        if (!path.startsWith(Path.of(config.getImageDir()).normalize())) {
            return ResponseEntity.notFound().build();
        }
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(path);
        String contentType = determineContentType(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String determineContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".tiff")) return "image/tiff";
        return "application/octet-stream";
    }

    // ─────────────────────────────────────────────
    // API: Connection & Models
    // ─────────────────────────────────────────────

    @PostMapping("/api/connect-test")
    @ResponseBody
    public Map<String, String> connectTest() {
        String msg = ollama.testConnection();
        return Map.of("status", msg);
    }

    @PostMapping("/api/refresh-models")
    @ResponseBody
    public Map<String, Object> refreshModels() {
        List<String> models = ollama.listModels();
        return Map.of("models", models, "default", models.isEmpty() ? "" : models.get(0));
    }

    // ─────────────────────────────────────────────
    // API: Scan
    // ─────────────────────────────────────────────

    @PostMapping("/api/scan")
    @ResponseBody
    public Map<String, Object> scanImages() {
        List<String> files = imageService.getImageFiles(config.getImageDir());
        if (files.isEmpty()) {
            return Map.of(
                    "status", "📭 未找到图片 (目录: " + config.getImageDir() + ")",
                    "files", List.of()
            );
        }
        return Map.of(
                "status", "📸 找到 " + files.size() + " 张图片 (目录: " + config.getImageDir() + ")",
                "files", files
        );
    }

    // ─────────────────────────────────────────────
    // API: Preview
    // ─────────────────────────────────────────────

    @PostMapping("/api/preview")
    @ResponseBody
    public Map<String, Object> preview(@RequestParam String filename) {
        if (filename == null || filename.isBlank()) {
            return Map.of("imageUrl", "", "info", "请先扫描并选择图片");
        }
        Path path = Path.of(config.getImageDir()).resolve(filename);
        if (!Files.exists(path)) {
            return Map.of("imageUrl", "", "info", "文件不存在: " + path);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageUrl", "/api/images/" + filename);

        Optional<AnalysisResult> cached = state.getResult(filename);
        if (cached.isPresent()) {
            AnalysisResult r = cached.get();
            String info = String.format(
                    "**已分析** | 类别: `%s` | 模型: `%s` | 时间: %s\n\n**响应:** %s",
                    r.getCategory(), r.getModel(), r.getTimestamp(), r.getResponse()
            );
            result.put("info", info);
        } else {
            result.put("info", "");
        }
        return result;
    }

    // ─────────────────────────────────────────────
    // API: Single Analysis
    // ─────────────────────────────────────────────

    @PostMapping("/api/analyze")
    @ResponseBody
    public CompletableFuture<Map<String, Object>> analyze(
            @RequestParam String filename,
            @RequestParam String prompt,
            @RequestParam String model,
            @RequestParam double temperature) {

        if (filename == null || filename.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "❌ 请先选择一张图片");
            return CompletableFuture.completedFuture(err);
        }
        if (prompt == null || prompt.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "❌ 请输入提示词");
            return CompletableFuture.completedFuture(err);
        }

        Path path = Path.of(config.getImageDir()).resolve(filename);
        if (!Files.exists(path)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "❌ 文件不存在: " + path);
            return CompletableFuture.completedFuture(err);
        }

        // Run on ollamaExecutor — Tomcat thread is released immediately
        return ollama.chatWithImageAsync(path.toString(), prompt, model, temperature)
                .thenApply(response -> {
                    String category = ollama.classifyResult(response, "");
                    if (category.length() > 50) category = category.substring(0, 50);

                    AnalysisResult result = new AnalysisResult(
                            filename, prompt, model, response, category,
                            "async");
                    state.setResult(filename, result);

                    String info = String.format(
                            "✅ 分析完成\n**类别:** `%s`\n**模型:** `%s`\n\n**响应:** %s",
                            category, model, response
                    );

                    Map<String, Object> resultMap = new LinkedHashMap<>();
                    resultMap.put("info", info);
                    resultMap.put("imageUrl", "/api/images/" + filename);
                    resultMap.put("category", category);
                    return resultMap;
                })
                .exceptionally(ex -> {
                    log.error("Analysis failed for {}", filename, ex);
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "❌ 分析失败: " + ex.getMessage());
                    return err;
                });
    }

    // ─────────────────────────────────────────────
    // API: Batch Processing
    // ─────────────────────────────────────────────

    @PostMapping("/api/batch")
    @ResponseBody
    public Map<String, Object> batchProcess(
            @RequestParam String prompt,
            @RequestParam String model,
            @RequestParam double temperature,
            @RequestParam(defaultValue = "50") int maxImages) {

        if (prompt == null || prompt.isBlank()) {
            return Map.of("error", "❌ 请输入提示词");
        }

        List<String> allFiles = imageService.getImageFiles(config.getImageDir());
        if (allFiles.isEmpty()) {
            return Map.of("error", "❌ 未找到图片");
        }

        // Launch background batch task — returns immediately with taskId
        String taskId = batchTaskService.startBatch(
                prompt, model, temperature, config.getImageDir(), maxImages);

        return Map.of("taskId", taskId, "status", "RUNNING");
    }

    @PostMapping("/api/batch/status")
    @ResponseBody
    public Map<String, Object> batchStatus(@RequestParam String taskId) {
        BatchTaskService.BatchTask task = batchTaskService.getTask(taskId);
        if (task == null) {
            return Map.of("error", "❌ 任务不存在或已过期");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.taskId);
        result.put("status", task.status.name());
        result.put("total", task.total);
        result.put("completed", task.completed);
        if (task.summary != null) {
            result.put("summary", task.summary);
        }
        if (task.csvUrl != null) {
            result.put("csvUrl", task.csvUrl);
        }
        return result;
    }

    // ─────────────────────────────────────────────
    // API: Results Filter & Categories
    // ─────────────────────────────────────────────

    @PostMapping("/api/categories")
    @ResponseBody
    public Map<String, Object> getCategories() {
        List<String> categories = state.getCategories();
        return Map.of("categories", categories);
    }

    @PostMapping("/api/filter")
    @ResponseBody
    public Map<String, Object> filterResults(@RequestParam(defaultValue = "全部") String category) {
        Map<String, Object> stats = state.getStats();
        List<AnalysisResult> filtered = state.filterResults(category);

        // Build markdown stats
        @SuppressWarnings("unchecked")
        Map<String, Integer> cats = (Map<String, Integer>) stats.get("categories");
        StringBuilder md = new StringBuilder();
        md.append("**总计:** ").append(stats.get("total")).append(" 张 | **当前筛选:** ")
                .append(category != null ? category : "全部").append("\n\n");
        if (cats != null) {
            cats.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> md.append("- `").append(e.getKey())
                            .append("`: ").append(e.getValue()).append("\n"));
        }

        // Build table rows
        List<List<String>> table = new ArrayList<>();
        for (AnalysisResult r : filtered) {
            String resp = r.getResponse() != null ? r.getResponse() : "";
            if (resp.length() > 200) resp = resp.substring(0, 200);
            table.add(List.of(
                    r.getFilename() != null ? r.getFilename() : "",
                    r.getCategory() != null ? r.getCategory() : "",
                    resp,
                    r.getTimestamp() != null ? r.getTimestamp() : ""
            ));
        }

        return Map.of("stats", md.toString(), "table", table);
    }

    // ─────────────────────────────────────────────
    // API: Export
    // ─────────────────────────────────────────────

    @PostMapping("/api/export")
    @ResponseBody
    public Map<String, Object> exportResults(@RequestParam(defaultValue = "全部") String category) {
        List<AnalysisResult> filtered = state.filterResults(category);
        if (filtered.isEmpty()) {
            return Map.of("status", "❌ 没有匹配的结果可导出", "csvUrl", "");
        }
        try {
            Path csvPath = csvExport.saveToCsv(filtered);
            String url = csvPath != null ? "/api/download/" + csvPath.getFileName().toString() : "";
            return Map.of(
                    "status", "✅ 已导出 " + filtered.size() + " 条结果",
                    "csvUrl", url
            );
        } catch (IOException e) {
            return Map.of("status", "❌ 导出失败: " + e.getMessage(), "csvUrl", "");
        }
    }

    @GetMapping("/api/download/{filename}")
    @ResponseBody
    public ResponseEntity<Resource> downloadCsv(@PathVariable String filename) {
        Path path = Path.of("filter_results").resolve(filename).normalize();
        if (!path.startsWith(Path.of("filter_results").normalize()) || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(resource);
    }

    // ─────────────────────────────────────────────
    // API: Clear
    // ─────────────────────────────────────────────

    @PostMapping("/api/clear")
    @ResponseBody
    public Map<String, Object> clearResults() {
        int count = state.getAllResults().size();
        state.clearResults();
        return Map.of("status", "🗑️ 已清除 " + count + " 条结果");
    }
}
