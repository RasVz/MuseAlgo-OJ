package com.musealgo.oj.community.notification;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Small dependency-free check runner for DATETIME conversion under isolated JVM zones.
 * Run after test compilation with this module's main and test classes on the classpath.
 */
public final class ReportNotificationDateTimesZoneCheck {
    private static final String WALL_TIME = "2026-10-21T08:09:10.123";

    private ReportNotificationDateTimesZoneCheck() { }

    public static void main(String[] args) throws Exception {
        String expected = LocalDateTime.parse(WALL_TIME)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toString();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        for (String zone : List.of("UTC", "America/Los_Angeles")) {
            Process child = new ProcessBuilder(
                    java,
                    "-Duser.timezone=" + zone,
                    "-cp", classpath,
                    ReportNotificationDateTimesZoneProbe.class.getName(),
                    WALL_TIME)
                    .redirectErrorStream(true)
                    .start();
            try {
                try (OutputStream childInput = child.getOutputStream();
                     InputStream childOutput = child.getInputStream();
                     InputStream childError = child.getErrorStream()) {
                    childInput.close();
                    boolean exited;
                    try {
                        exited = child.waitFor(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        try {
                            forceAndConfirmExit(child);
                        } catch (AssertionError cleanupFailure) {
                            interrupted.addSuppressed(cleanupFailure);
                        }
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                    if (!exited) {
                        forceAndConfirmExit(child);
                        throw new AssertionError("Child JVM timed out and was forcibly terminated for zone " + zone);
                    }
                    String output = new String(childOutput.readAllBytes(), StandardCharsets.UTF_8).trim();
                    int exitCode = child.exitValue();
                    if (exitCode != 0 || !expected.equals(output)) {
                        throw new AssertionError("Zone " + zone + " expected " + expected
                                + " but child exited " + exitCode + " with: " + output);
                    }
                }
            } finally {
                if (child.isAlive()) forceAndConfirmExit(child);
            }
        }
        System.out.println("PASS: Asia/Shanghai DATETIME conversion stable in UTC and America/Los_Angeles JVMs");
    }

    private static void forceAndConfirmExit(Process child) {
        if (!child.isAlive()) return;
        child.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (child.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("Child JVM remained alive after forced termination request");
                }
                try {
                    child.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (child.isAlive()) {
            throw new AssertionError("Child JVM did not exit after forced termination request");
        }
    }
}
