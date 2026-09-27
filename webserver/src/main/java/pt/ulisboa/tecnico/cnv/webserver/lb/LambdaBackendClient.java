package pt.ulisboa.tecnico.cnv.webserver.lb;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class LambdaBackendClient implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_FRACTALS_FUNCTION = "CNV-NatureCloud-Fractals";
    private static final String DEFAULT_GRAYSCOTT_FUNCTION = "CNV-NatureCloud-GrayScott";
    private static final String DEFAULT_DNA_FUNCTION = "CNV-NatureCloud-Dna";

    private static final long DEFAULT_MAX_ESTIMATED_INSTRUCTIONS = 60_000_000_000L;

    private final boolean enabled;
    private final long maxEstimatedInstructions;

    private final String fractalsFunctionName;
    private final String grayScottFunctionName;
    private final String dnaFunctionName;

    private final LambdaClient lambdaClient;

    public LambdaBackendClient() {
        this.enabled = "true".equalsIgnoreCase(envOrDefault("CNV_LAMBDA_ENABLED", "false"));
        this.maxEstimatedInstructions = longEnvOrDefault(
                "CNV_LAMBDA_MAX_ESTIMATED_INSTRUCTIONS",
                DEFAULT_MAX_ESTIMATED_INSTRUCTIONS
        );

        this.fractalsFunctionName = envOrDefault(
                "CNV_LAMBDA_FRACTALS_FUNCTION",
                DEFAULT_FRACTALS_FUNCTION
        );
        this.grayScottFunctionName = envOrDefault(
                "CNV_LAMBDA_GRAYSCOTT_FUNCTION",
                DEFAULT_GRAYSCOTT_FUNCTION
        );
        this.dnaFunctionName = envOrDefault(
                "CNV_LAMBDA_DNA_FUNCTION",
                DEFAULT_DNA_FUNCTION
        );

        if (enabled) {
            this.lambdaClient = LambdaClient.builder()
                    .region(resolveRegion())
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .build();

            System.out.printf(
                    "[Lambda] enabled fractals=%s grayscott=%s dna=%s maxEstimatedInstructions=%d%n",
                    fractalsFunctionName,
                    grayScottFunctionName,
                    dnaFunctionName,
                    maxEstimatedInstructions
            );
        } else {
            this.lambdaClient = null;
            System.out.println("[Lambda] disabled.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean canInvoke(String workload, Map<String, String> params, long estimatedInstructions) {
        if (!enabled || lambdaClient == null) {
            return false;
        }

        if (estimatedInstructions <= 0L || estimatedInstructions > maxEstimatedInstructions) {
            return false;
        }

        return functionNameFor(workload) != null;
    }

    public LambdaResult invoke(
            String workload,
            Map<String, String> params,
            long estimatedInstructions
    ) throws IOException {
        if (!canInvoke(workload, params, estimatedInstructions)) {
            throw new IOException("Lambda backend is not enabled or request is not Lambda-safe.");
        }

        String functionName = functionNameFor(workload);

        Map<String, String> payloadMap = new HashMap<>(params);
        String payload = OBJECT_MAPPER.writeValueAsString(payloadMap);

        InvokeRequest request = InvokeRequest.builder()
                .functionName(functionName)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromUtf8String(payload))
                .build();

        long startNanos = System.nanoTime();

        try {
            System.out.printf(
                    "[Lambda] invoking workload=%s function=%s estimatedInstructions=%d%n",
                    workload,
                    functionName,
                    estimatedInstructions
            );

            InvokeResponse response = lambdaClient.invoke(request);

            long elapsedNanos = System.nanoTime() - startNanos;

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Lambda invoke failed with status " + response.statusCode());
            }

            if (response.functionError() != null && !response.functionError().isBlank()) {
                String rawError = response.payload() == null
                        ? ""
                        : response.payload().asUtf8String();

                throw new IOException(
                        "Lambda function error: " + response.functionError() + " " + rawError
                );
            }

            String rawPayload = response.payload() == null
                    ? ""
                    : response.payload().asUtf8String();

            String body = decodeLambdaStringPayload(rawPayload);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            System.out.printf(
                    "[Lambda] completed workload=%s function=%s elapsedNanos=%d responseBytes=%d%n",
                    workload,
                    functionName,
                    elapsedNanos,
                    bodyBytes.length
            );

            return new LambdaResult(
                    200,
                    contentTypeForBody(body),
                    bodyBytes,
                    elapsedNanos
            );

        } catch (SdkException e) {
            throw new IOException("Lambda SDK error: " + e.getMessage(), e);
        }
    }

    private String functionNameFor(String workload) {
        if (workload == null) {
            return null;
        }

        switch (workload.trim().toLowerCase()) {
            case "fractals":
                return fractalsFunctionName;
            case "grayscott":
                return grayScottFunctionName;
            case "dna":
                return dnaFunctionName;
            default:
                return null;
        }
    }

    private static String decodeLambdaStringPayload(String rawPayload) throws IOException {
        if (rawPayload == null || rawPayload.isBlank()) {
            return "";
        }

        try {
            return OBJECT_MAPPER.readValue(rawPayload, String.class);
        } catch (RuntimeException e) {
            return rawPayload;
        }
    }

    private static String contentTypeForBody(String body) {
        if (body == null) {
            return "text/plain; charset=utf-8";
        }

        String trimmed = body.trim().toLowerCase();

        if (trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html")) {
            return "text/html; charset=utf-8";
        }

        return "text/plain; charset=utf-8";
    }

    @Override
    public void close() {
        if (lambdaClient != null) {
            lambdaClient.close();
        }
    }

    private static Region resolveRegion() {
        String region = firstNonBlank(
                System.getProperty("aws.region"),
                System.getenv("AWS_REGION"),
                System.getenv("AWS_DEFAULT_REGION")
        );

        if (region == null) {
            return Region.US_EAST_1;
        }

        return Region.of(region);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return value.trim();
    }

    private static long longEnvOrDefault(String name, long defaultValue) {
        String value = System.getenv(name);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            long parsed = Long.parseLong(value.trim());

            if (parsed <= 0L) {
                return defaultValue;
            }

            return parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }

        return null;
    }

    public static final class LambdaResult {
        private final int statusCode;
        private final String contentType;
        private final byte[] body;
        private final long elapsedNanos;

        public LambdaResult(int statusCode, String contentType, byte[] body, long elapsedNanos) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body;
            this.elapsedNanos = elapsedNanos;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getBody() {
            return body;
        }

        public long getElapsedNanos() {
            return elapsedNanos;
        }
    }
}