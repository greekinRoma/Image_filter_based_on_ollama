package com.example.imagefilter.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Predefined prompts for image analysis — mirrors the Python version.
 */
public final class PredefinedPrompt {

    public static final String CUSTOM_KEY = "✏️ 自定义提示词 (Custom Prompt...)";

    private static final Map<String, String> PROMPTS = new LinkedHashMap<>();

    static {
        PROMPTS.put("📝 通用描述 (General Description)",
                "请用中文简要描述这张图片的内容。描述画面中的场景、人物、物体和整体氛围。");

        PROMPTS.put("🔍 质量筛选 (Quality Check)",
                "Check this image quality. Is it clear/readable? Rate as: "
                        + "GOOD (sharp, well-lit, usable) / OK (acceptable) / BAD (blurry, too dark, unreadable). "
                        + "Answer with ONLY one word: GOOD, OK, or BAD.");

        PROMPTS.put("📄 文档判断 (Document Detection)",
                "Is this image a document/scan/screenshot of text? "
                        + "Answer ONLY with: DOCUMENT or NOT_DOCUMENT.");

        PROMPTS.put("🖼️ 照片判断 (Photo Detection)",
                "Is this a real-world photograph (not a screenshot, document, or drawing)? "
                        + "Answer ONLY with: PHOTO or NOT_PHOTO.");

        PROMPTS.put("👤 人物检测 (Person Detection)",
                "Does this image contain any people/humans? "
                        + "Answer ONLY with: HAS_PERSON or NO_PERSON.");

        PROMPTS.put("🌗 亮度分类 (Brightness Classify)",
                "How bright is this image? Answer with ONLY one word: "
                        + "BRIGHT, NORMAL, or DARK.");

        PROMPTS.put("🎨 色彩判断 (Color Type)",
                "Is this image mostly: COLORFUL, MONOCHROME (black&white/grayscale), "
                        + "or SEPIA (warm-tinted old photo)? Answer with ONLY one word.");

        PROMPTS.put("🧾 截图检测 (Screenshot Detection)",
                "Is this a screenshot of a computer/mobile interface (UI, code, terminal, app)? "
                        + "Answer ONLY with: SCREENSHOT or NOT_SCREENSHOT.");

        PROMPTS.put("🏞️ 自然场景 (Nature Scene)",
                "Is this image primarily showing nature (landscape, forest, ocean, sky, animals)? "
                        + "Answer ONLY with: NATURE or NOT_NATURE.");

        PROMPTS.put(CUSTOM_KEY, "__CUSTOM__");
    }

    private PredefinedPrompt() {}

    public static Map<String, String> getAll() {
        return new LinkedHashMap<>(PROMPTS);
    }

    public static String get(String key) {
        return PROMPTS.getOrDefault(key, "__CUSTOM__");
    }

    public static boolean isCustom(String key) {
        return "__CUSTOM__".equals(PROMPTS.get(key));
    }
}
