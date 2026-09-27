package pt.ulisboa.tecnico.cnv.webserver.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB-backed implementation of MetricsRepository.
 *
 * It also keeps a small local in-memory cache so the LB/AS can still compute
 * recent averages without scanning DynamoDB on every decision.
 *
 * DynamoDB writes are best-effort: a failed metrics write must not fail the
 * user request.
 */
public final class DynamoDbMetricsRepository implements MetricsRepository, AutoCloseable {

    public static final String DEFAULT_TABLE_NAME = "NatureCloudMetrics";

    private final InMemoryMetricsRepository localCache;
    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final boolean enabled;

    public DynamoDbMetricsRepository() {
        this(
                tableNameFromEnvironment(),
                enabledFromEnvironment()
        );
    }

    public DynamoDbMetricsRepository(String tableName, boolean enabled) {
        this.localCache = new InMemoryMetricsRepository();
        this.tableName = normalizeTableName(tableName);
        this.enabled = enabled;

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

    public DynamoDbMetricsRepository(DynamoDbClient dynamoDb, String tableName) {
        this.localCache = new InMemoryMetricsRepository();
        this.dynamoDb = dynamoDb;
        this.tableName = normalizeTableName(tableName);
        this.enabled = dynamoDb != null;
    }

    @Override
    public void recordCompletedRequest(CompletedRequestMetric metric) {
        if (metric == null) {
            return;
        }

        localCache.recordCompletedRequest(metric);

        if (!enabled || dynamoDb == null) {
            return;
        }

        try {
            PutItemRequest request = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(toDynamoItem(metric))
                    .build();

            dynamoDb.putItem(request);
        } catch (SdkException e) {
            System.err.println("[DynamoDbMetricsRepository] failed to write metric to DynamoDB: "
                    + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("[DynamoDbMetricsRepository] unexpected error writing metric: "
                    + e.getMessage());
        }
    }

    @Override
    public List<CompletedRequestMetric> recent(int limit) {
        return localCache.recent(limit);
    }

    public List<CompletedRequestMetric> recentSuccessful(int limit) {
        return localCache.recentSuccessful(limit);
    }

    @Override
    public double averageInstructionsPerSecond() {
        return localCache.averageInstructionsPerSecond();
    }

    @Override
    public double averageInstructionsPerSecond(String workload) {
        return localCache.averageInstructionsPerSecond(workload);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public void close() {
        if (dynamoDb != null) {
            dynamoDb.close();
        }
    }

    private static Map<String, AttributeValue> toDynamoItem(CompletedRequestMetric metric) {
        String requestId = metric.getCompletedAtMillis() + "-" + UUID.randomUUID();

        Map<String, AttributeValue> item = new HashMap<>();

        item.put("workload", s(metric.getWorkload()));
        item.put("requestId", s(requestId));
        item.put("query", s(metric.getQuery()));
        item.put("workerId", s(metric.getWorkerId()));

        item.put("estimatedInstructions", n(metric.getEstimatedInstructions()));
        item.put("actualInstructions", n(metric.getActualInstructions()));
        item.put("elapsedNanos", n(metric.getElapsedNanos()));
        item.put("completedAtMillis", n(metric.getCompletedAtMillis()));

        item.put("threadId", n(metric.getThreadId()));
        item.put("methods", n(metric.getMethods()));
        item.put("basicBlocks", n(metric.getBasicBlocks()));

        item.put("status", s(metric.getStatus()));
        item.put("success", AttributeValue.builder().bool(metric.isSuccessful()).build());
        item.put("errorMessage", s(metric.getErrorMessage()));

        return item;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder()
                .s(value == null ? "" : value)
                .build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder()
                .n(Long.toString(value))
                .build();
    }

    private static String tableNameFromEnvironment() {
        String value = System.getenv("CNV_METRICS_TABLE");

        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_TABLE_NAME;
        }

        return value.trim();
    }

    private static boolean enabledFromEnvironment() {
        String value = System.getenv("CNV_METRICS_DYNAMODB_ENABLED");

        return value != null && value.equalsIgnoreCase("true");
    }

    private static String normalizeTableName(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            return DEFAULT_TABLE_NAME;
        }

        return tableName.trim();
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
}