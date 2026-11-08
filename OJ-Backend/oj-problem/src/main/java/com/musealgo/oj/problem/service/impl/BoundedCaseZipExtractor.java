package com.musealgo.oj.problem.service.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts into a fresh private directory, enforcing limits before each write. */
final class BoundedCaseZipExtractor {
    private BoundedCaseZipExtractor() { }

    static void extract(Path archive, Path directory, long maxBytes, int maxEntries) throws IOException {
        Path root = directory.toRealPath();
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        // Stream entries so the entry cap also bounds parsing before extraction;
        // do not materialize an unbounded ZIP central directory first.
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > maxEntries) {
                    throw new IllegalArgumentException("ZIP 文件条目超过上限 " + maxEntries);
                }
                String name = entry.getName();
                // Reject both platforms' path syntax before creating anything.
                if (name.isBlank() || name.contains("\\") || name.matches("^[A-Za-z]:.*")) {
                    throw new IllegalArgumentException("ZIP 包含非法路径");
                }
                Path relative = Path.of(name);
                for (Path component : relative) {
                    if (component.toString().equals("..")) {
                        throw new IllegalArgumentException("ZIP 包含非法路径");
                    }
                }
                Path target = root.resolve(relative).normalize();
                if (relative.isAbsolute() || target.equals(root) || !target.startsWith(root)) {
                    throw new IllegalArgumentException("ZIP 包含非法路径");
                }
                if (entry.isDirectory()) {
                    if (zip.read() != -1) {
                        throw new IllegalArgumentException("ZIP 目录条目包含文件数据");
                    }
                    Files.createDirectories(target);
                    continue;
                }
                if (entry.getSize() > maxBytes - total) {
                    throw new IllegalArgumentException("ZIP 解压后超过大小上限");
                }
                Files.createDirectories(target.getParent());
                // CREATE_NEW rejects duplicate entries; no archive entry creates a link.
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        if (read > maxBytes - total) {
                            throw new IllegalArgumentException("ZIP 解压后超过大小上限");
                        }
                        output.write(buffer, 0, read);
                        total += read;
                    }
                }
            }
        }
    }
}
