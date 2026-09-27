package pt.ulisboa.tecnico.cnv.webserver.cost;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public final class DynamoDbBackedCostEstimator implements CostEstimator<Map<String, String>>, AutoCloseable {

    private static final String DEFAULT_TABLE_NAME = "NatureCloudMetrics";
    private static final int DEFAULT_HISTORY_LIMIT = 200;
    private static final int DEFAULT_REFRESH_SECONDS = 30;

    private final String workload;
    private final CostEstimator<Map<String, String>> fallbackEstimator;
    private final String tableName;
    private final boolean enabled;
    private final int historyLimit;
    private final long refreshMillis;
    private final DynamoDbClient dynamoDb;

    private volatile CostSnapshot snapshot = CostSnapshot.empty();
    private volatile long lastRefreshMillis = 0L;

    public DynamoDbBackedCostEstimator(String workload, CostEstimator<Map<String, String>> fallbackEstimator) {
        this.workload = workload == null ? "" : workload.trim().toLowerCase();
        this.fallbackEstimator = fallbackEstimator;
        this.tableName = envOrDefault("CNV_METRICS_TABLE", DEFAULT_TABLE_NAME);
        this.enabled = !"false".equalsIgnoreCase(envOrDefault("CNV_DYNAMIC_COST_MODEL_ENABLED", "true"));
        this.historyLimit = intEnvOrDefault("CNV_COST_HISTORY_LIMIT", DEFAULT_HISTORY_LIMIT);
        this.refreshMillis = 1000L * intEnvOrDefault("CNV_COST_REFRESH_SECONDS", DEFAULT_REFRESH_SECONDS);

        if (enabled) {
            this.dynamoDb = DynamoDbClient.builder()
                    .region(resolveRegion())
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .build();
        } else {
            this.dynamoDb = null;
        }
    }

    public static DynamoDbBackedCostEstimator forWorkload(
            String workload,
            CostEstimator<Map<String, String>> fallbackEstimator
    ) {
        return new DynamoDbBackedCostEstimator(workload, fallbackEstimator);
    }

    @Override
    public long estimate(Map<String, String> params) {
        long fallbackEstimate = safeFallbackEstimate(params);

        if (!enabled || dynamoDb == null) {
            return fallbackEstimate;
        }

        refreshIfNeeded();

        String exactKey = canonicalKey(params);
        double featureUnits = featureUnits(workload, params);

        long dynamicEstimate = snapshot.estimate(exactKey, featureUnits);

        if (dynamicEstimate > 0) {
            System.out.printf(
                    "[CostModel] workload=%s source=dynamodb estimate=%d fallback=%d samples=%d ratioSamples=%d%n",
                    workload,
                    dynamicEstimate,
                    fallbackEstimate,
                    snapshot.getTotalSamples(),
                    snapshot.getRatioSamples()
            );
            return dynamicEstimate;
        }

        System.out.printf(
                "[CostModel] workload=%s source=fallback estimate=%d samples=%d ratioSamples=%d%n",
                workload,
                fallbackEstimate,
                snapshot.getTotalSamples(),
                snapshot.getRatioSamples()
        );

        return fallbackEstimate;
    }

    @Override
    public String workloadName() {
        return workload;
    }

    @Override
    public void close() {
        if (dynamoDb != null) {
            dynamoDb.close();
        }
    }

    private long safeFallbackEstimate(Map<String, String> params) {
        try {
            long estimate = fallbackEstimator.estimate(params);
            return Math.max(1L, estimate);
        } catch (RuntimeException e) {
            return Math.max(1L, Math.round(featureUnits(workload, params)));
        }
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();

        if (now - lastRefreshMillis < refreshMillis) {
            return;
        }

        synchronized (this) {
            now = System.currentTimeMillis();

            if (now - lastRefreshMillis < refreshMillis) {
                return;
            }

            try {
                CostSnapshot newSnapshot = loadSnapshotFromDynamoDb();

                this.snapshot = newSnapshot;
                this.lastRefreshMillis = now;

                System.out.printf(
                        "[CostModel] refreshed workload=%s table=%s samples=%d ratioSamples=%d avgRatio=%.6f%n",
                        workload,
                        tableName,
                        newSnapshot.getTotalSamples(),
                        newSnapshot.getRatioSamples(),
                        newSnapshot.getAverageInstructionsPerFeatureUnit()
                );
            } catch (SdkException e) {
                this.lastRefreshMillis = now;
                System.err.println("[CostModel] failed to refresh from DynamoDB: " + e.getMessage());
            } catch (RuntimeException e) {
                this.lastRefreshMillis = now;
                System.err.println("[CostModel] unexpected refresh error: " + e.getMessage());
            }
        }
    }

    private CostSnapshot loadSnapshotFromDynamoDb() {
        Map<String, LongSummaryStatistics> exactStats = new HashMap<>();
        ArrayList<Double> ratios = new ArrayList<>();

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":w", AttributeValue.builder().s(workload).build());
        expressionValues.put(":success", AttributeValue.builder().s("SUCCESS").build());
        expressionValues.put(":true", AttributeValue.builder().bool(true).build());

        Map<String, String> expressionNames = new HashMap<>();
        expressionNames.put("#status", "status");

        Map<String, AttributeValue> exclusiveStartKey = null;

        int pages = 0;
        int totalSamples = 0;

        do {
            QueryRequest.Builder requestBuilder = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("workload = :w")
                    .filterExpression("#status = :success AND success = :true")
                    .expressionAttributeNames(expressionNames)
                    .expressionAttributeValues(expressionValues)
                    .scanIndexForward(false)
                    .limit(historyLimit);

            if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
                requestBuilder.exclusiveStartKey(exclusiveStartKey);
            }

            QueryResponse response = dynamoDb.query(requestBuilder.build());

            for (Map<String, AttributeValue> item : response.items()) {
                HistoricalSample sample = toSample(item);

                if (sample == null || sample.actualInstructions <= 0) {
                    continue;
                }

                exactStats
                        .computeIfAbsent(sample.exactKey, key -> new LongSummaryStatistics())
                        .accept(sample.actualInstructions);

                if (sample.featureUnits > 0.0) {
                    ratios.add(sample.actualInstructions / sample.featureUnits);
                }

                totalSamples++;

                if (totalSamples >= historyLimit) {
                    break;
                }
            }

            exclusiveStartKey = response.lastEvaluatedKey();
            pages++;

        } while (
                totalSamples < historyLimit
                        && exclusiveStartKey != null
                        && !exclusiveStartKey.isEmpty()
                        && pages < 5
        );

        Map<String, Long> exactAverages = new HashMap<>();

        for (Map.Entry<String, LongSummaryStatistics> entry : exactStats.entrySet()) {
            exactAverages.put(entry.getKey(), Math.round(entry.getValue().getAverage()));
        }

        double averageRatio = ratios.stream()
                .filter(value -> value > 0.0 && Double.isFinite(value))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        return new CostSnapshot(exactAverages, totalSamples, ratios.size(), averageRatio);
    }

    private HistoricalSample toSample(Map<String, AttributeValue> item) {
        AttributeValue queryValue = item.get("query");
        AttributeValue actualValue = item.get("actualInstructions");

        if (queryValue == null || actualValue == null || queryValue.s() == null || actualValue.n() == null) {
            return null;
        }

        String query = queryValue.s();
        long actualInstructions = parseLong(actualValue.n(), 0L);

        Map<String, String> params = parseQuery(query);
        String exactKey = canonicalKey(params);
        double featureUnits = featureUnits(workload, params);

        return new HistoricalSample(exactKey, featureUnits, actualInstructions);
    }

    private static double featureUnits(String workload, Map<String, String> params) {
        switch (workload) {
            case "fractals":
                return positiveProduct(
                        longParam(params, "w", 0L),
                        longParam(params, "h", 0L),
                        longParam(params, "iterations", 0L)
                );

            case "grayscott":
                long size = longParam(params, "size", 0L);
                long maxIterations = longParam(params, "maxIterations", 0L);
                return positiveProduct(size, size, maxIterations);

            case "dna":
                int len1 = sequenceLength(params.get("seq1"));
                int len2 = sequenceLength(params.get("seq2"));
                return positiveProduct(len1, len2);

            default:
                return params.values()
                        .stream()
                        .mapToDouble(DynamoDbBackedCostEstimator::safeDouble)
                        .filter(value -> value > 0.0)
                        .sum();
        }
    }

    private static double positiveProduct(long... values) {
        double result = 1.0;

        for (long value : values) {
            if (value <= 0) {
                return 0.0;
            }

            result *= value;

            if (!Double.isFinite(result)) {
                return 0.0;
            }
        }

        return result;
    }

    private static int sequenceLength(String value) {
        if (value == null) {
            return 0;
        }

        int colon = value.indexOf(':');

        if (colon >= 0 && colon + 1 < value.length()) {
            return value.length() - colon - 1;
        }

        return value.length();
    }

    private static String canonicalKey(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);

        StringJoiner joiner = new StringJoiner("&");

        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            joiner.add(entry.getKey() + "=" + entry.getValue());
        }

        return joiner.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();

        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }

        String[] pairs = rawQuery.split("&");

        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }

            int equalsIndex = pair.indexOf('=');

            String rawKey;
            String rawValue;

            if (equalsIndex >= 0) {
                rawKey = pair.substring(0, equalsIndex);
                rawValue = pair.substring(equalsIndex + 1);
            } else {
                rawKey = pair;
                rawValue = "";
            }

            params.put(decode(rawKey), decode(rawValue));
        }

        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static long longParam(Map<String, String> params, String key, long defaultValue) {
        String value = params.get(key);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return parseLong(value, defaultValue);
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private static double safeDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException e) {
            return 0.0;
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

    private static int intEnvOrDefault(String name, int defaultValue) {
        String value = System.getenv(name);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.trim());

            if (parsed <= 0) {
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

    private static final class HistoricalSample {
        private final String exactKey;
        private final double featureUnits;
        private final long actualInstructions;

        private HistoricalSample(String exactKey, double featureUnits, long actualInstructions) {
            this.exactKey = exactKey;
            this.featureUnits = featureUnits;
            this.actualInstructions = actualInstructions;
        }
    }

    private static final class CostSnapshot {
        private final Map<String, Long> exactAverages;
        private final int totalSamples;
        private final int ratioSamples;
        private final double averageInstructionsPerFeatureUnit;

        private CostSnapshot(
                Map<String, Long> exactAverages,
                int totalSamples,
                int ratioSamples,
                double averageInstructionsPerFeatureUnit
        ) {
            this.exactAverages = new HashMap<>(exactAverages);
            this.totalSamples = totalSamples;
            this.ratioSamples = ratioSamples;
            this.averageInstructionsPerFeatureUnit = averageInstructionsPerFeatureUnit;
        }

        private static CostSnapshot empty() {
            return new CostSnapshot(Map.of(), 0, 0, 0.0);
        }

        private long estimate(String exactKey, double featureUnits) {
            Long exactAverage = exactAverages.get(exactKey);

            if (exactAverage != null && exactAverage > 0L) {
                return exactAverage;
            }

            if (averageInstructionsPerFeatureUnit > 0.0 && featureUnits > 0.0) {
                double estimate = averageInstructionsPerFeatureUnit * featureUnits;

                if (Double.isFinite(estimate) && estimate > 0.0) {
                    return Math.max(1L, Math.round(estimate));
                }
            }

            return -1L;
        }

        private int getTotalSamples() {
            return totalSamples;
        }

        private int getRatioSamples() {
            return ratioSamples;
        }

        private double getAverageInstructionsPerFeatureUnit() {
            return averageInstructionsPerFeatureUnit;
        }
    }
}