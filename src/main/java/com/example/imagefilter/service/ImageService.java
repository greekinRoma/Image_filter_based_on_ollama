package com.example.imagefilter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans directories for image files and provides file metadata.
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private static final Set<String> EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".bmp", ".webp", ".gif", ".tiff"
    );

    /**
     * Get a sorted list of image filenames from a directory.
     */
    public List<String> getImageFiles(String imgDir) {
        Path dir = Path.of(imgDir);
        if (!Files.isDirectory(dir)) {
            log.warn("Image directory not found: {}", imgDir);
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> {
                        String lower = name.toLowerCase();
                        return EXTENSIONS.stream().anyMatch(lower::endsWith);
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to scan image directory: {}", imgDir, e);
            return List.of();
        }
    }

    /**
     * Check if a file exists and is a supported image type.
     */
    public boolean isImage(Path path) {
        if (!Files.exists(path)) return false;
        String lower = path.getFileName().toString().toLowerCase();
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
