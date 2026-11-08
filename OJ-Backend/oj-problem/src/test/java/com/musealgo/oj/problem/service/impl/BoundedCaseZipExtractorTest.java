package com.musealgo.oj.problem.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedCaseZipExtractorTest {
    @TempDir Path temp;

    private Path archive(Map<String, String> entries) throws Exception {
        Path zip = temp.resolve("cases.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return zip;
    }

    @Test void acceptsNestedPairsExactlyAtByteLimit() throws Exception {
        Path zip = archive(Map.of("cases/1.in", "1234", "cases/1.out", "5678"));
        Path output = Files.createDirectory(temp.resolve("output"));
        BoundedCaseZipExtractor.extract(zip, output, 8, 2);
        assertEquals("1234", Files.readString(output.resolve("cases/1.in")));
        assertEquals("5678", Files.readString(output.resolve("cases/1.out")));
    }

    @Test void rejectsAggregateOverflowBeforeWritingExcessEntry() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("1.in", "1234");
        entries.put("1.out", "56789");
        Path zip = archive(entries);
        Path output = Files.createDirectory(temp.resolve("output"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedCaseZipExtractor.extract(zip, output, 8, 10));
        assertEquals(4, Files.size(output.resolve("1.in")));
        assertEquals(0, Files.size(output.resolve("1.out")));
    }

    @Test void countsActualBytesEvenWhenZipMetadataUnderstatesSize() throws Exception {
        Path zip = archive(Map.of("1.in", "123456789"));
        byte[] bytes = Files.readAllBytes(zip);
        for (int i = 0; i + 28 <= bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                java.util.Arrays.fill(bytes, i + 24, i + 28, (byte) 0);
                break;
            }
        }
        Files.write(zip, bytes);
        Path output = Files.createDirectory(temp.resolve("output"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedCaseZipExtractor.extract(zip, output, 8, 10));
        assertTrue(Files.size(output.resolve("1.in")) <= 8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.in", "/absolute.in", "nested/../../outside.in", "C:/outside.in", "..\\outside.in"})
    void rejectsPathsBeforeCreatingFiles(String name) throws Exception {
        Path zip = archive(Map.of(name, "x"));
        Path output = Files.createDirectory(temp.resolve("output"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedCaseZipExtractor.extract(zip, output, 16, 10));
        try (var files = Files.list(output)) {
            assertEquals(0, files.count());
        }
        assertFalse(Files.exists(temp.resolve("outside.in")));
    }

    @Test void capsEmptyFileCountAsWellAsBytes() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("first.in", "");
        entries.put("second.in", "");
        Path zip = archive(entries);
        Path output = Files.createDirectory(temp.resolve("output"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedCaseZipExtractor.extract(zip, output, 16, 1));
        assertTrue(Files.exists(output.resolve("first.in")));
        assertFalse(Files.exists(output.resolve("second.in")));
    }

    @Test void directoryEntriesCannotHideUncountedCompressedData() throws Exception {
        Path zip = archive(Map.of("directory/", "unexpected-data"));
        Path output = Files.createDirectory(temp.resolve("output"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedCaseZipExtractor.extract(zip, output, 8, 10));
        assertFalse(Files.exists(output.resolve("directory")));
    }
}
