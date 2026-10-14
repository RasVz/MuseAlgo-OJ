package top.everblue.oj.problem.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestCaseStorageServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void writesAndReadsImportedCaseFilesInAConfiguredWritableDirectory() {
        TestCaseStorageService storage = new TestCaseStorageService(storageRoot.toString());

        String inputPath = storage.writeInput(42L, 99L, "1 2\n");
        String outputPath = storage.writeOutput(42L, 99L, "3\n");

        assertThat(inputPath).isEqualTo("42/99/input.txt");
        assertThat(outputPath).isEqualTo("42/99/output.txt");
        assertThat(storage.readByRelativePath(inputPath)).isEqualTo("1 2\n");
        assertThat(storage.readByRelativePath(outputPath)).isEqualTo("3\n");
    }
}
