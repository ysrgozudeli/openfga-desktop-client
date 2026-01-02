package com.openfga.client.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Service for transforming OpenFGA DSL to JSON using the FGA CLI.
 */
public class DslTransformService {

    private static final String FGA_CLI = "fga";
    private static final int TIMEOUT_SECONDS = 30;

    /**
     * Check if the FGA CLI is available on the system.
     */
    public boolean isCliAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(FGA_CLI, "version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate DSL syntax using the FGA CLI.
     */
    public TransformResult validateDsl(String dsl) {
        try {
            // Write DSL to temp file
            Path tempFile = Files.createTempFile("openfga-model", ".fga");
            Files.writeString(tempFile, dsl, StandardCharsets.UTF_8);

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        FGA_CLI, "model", "validate",
                        "--file", tempFile.toString()
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                String output = readProcessOutput(process);

                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return TransformResult.failure("Validation timed out");
                }

                if (process.exitValue() == 0) {
                    return TransformResult.success(null);
                } else {
                    return TransformResult.failure(output);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            return TransformResult.failure("Failed to validate: " + e.getMessage());
        }
    }

    /**
     * Transform DSL to JSON using the FGA CLI.
     */
    public TransformResult transformDslToJson(String dsl) {
        try {
            // Write DSL to temp file
            Path tempFile = Files.createTempFile("openfga-model", ".fga");
            Files.writeString(tempFile, dsl, StandardCharsets.UTF_8);

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        FGA_CLI, "model", "transform",
                        "--file", tempFile.toString()
                );

                Process process = pb.start();

                // Read stdout (the JSON) and stderr separately
                String json = readProcessOutput(process);
                String errorOutput = readErrorOutput(process);

                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return TransformResult.failure("Transformation timed out");
                }

                if (process.exitValue() == 0) {
                    return TransformResult.success(json.trim());
                } else {
                    String error = errorOutput.isBlank() ? json : errorOutput;
                    return TransformResult.failure(error);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            return TransformResult.failure("Failed to transform: " + e.getMessage());
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private String readErrorOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Result of a DSL transformation or validation.
     */
    public static class TransformResult {
        private final boolean success;
        private final String json;
        private final String error;

        private TransformResult(boolean success, String json, String error) {
            this.success = success;
            this.json = json;
            this.error = error;
        }

        public static TransformResult success(String json) {
            return new TransformResult(true, json, null);
        }

        public static TransformResult failure(String error) {
            return new TransformResult(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getJson() {
            return json;
        }

        public String getError() {
            return error;
        }
    }
}
