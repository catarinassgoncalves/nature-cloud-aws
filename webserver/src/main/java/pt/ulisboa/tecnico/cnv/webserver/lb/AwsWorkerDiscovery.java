package pt.ulisboa.tecnico.cnv.webserver.lb;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.Instance;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers currently active EC2 workers from an Auto Scaling Group.
 *
 * For local testing from the VM, use public addresses:
 *   CNV_WORKER_ADDRESS_MODE=public
 *
 * For final deployment where the Java LB runs inside AWS, use private addresses:
 *   CNV_WORKER_ADDRESS_MODE=private
 *
 * The discovered worker port is controlled by CNV_DISCOVERED_WORKER_PORT.
 * This is intentionally separate from CNV_WORKER_PORT, because CNV_WORKER_PORT
 * is used when starting local worker WebServers on ports like 8001/8002.
 */
public final class AwsWorkerDiscovery implements AutoCloseable {

    private static final String DEFAULT_ASG_NAME = "CNV-NatureCloud-ASG";
    private static final String DEFAULT_ADDRESS_MODE = "public";
    private static final int DEFAULT_DISCOVERED_WORKER_PORT = 8000;

    private final AutoScalingClient autoScalingClient;
    private final Ec2Client ec2Client;
    private final String autoScalingGroupName;
    private final String addressMode;
    private final int workerPort;

    public AwsWorkerDiscovery() {
        this(
                envOrDefault("CNV_ASG_NAME", DEFAULT_ASG_NAME),
                envOrDefault("CNV_WORKER_ADDRESS_MODE", DEFAULT_ADDRESS_MODE),
                intEnvOrDefault("CNV_DISCOVERED_WORKER_PORT", DEFAULT_DISCOVERED_WORKER_PORT)
        );
    }

    public AwsWorkerDiscovery(String autoScalingGroupName, String addressMode, int workerPort) {
        this.autoScalingGroupName = autoScalingGroupName;
        this.addressMode = normalizeAddressMode(addressMode);
        this.workerPort = workerPort;

        Region region = resolveRegion();

        this.autoScalingClient = AutoScalingClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        this.ec2Client = Ec2Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    public Map<String, String> discoverWorkers() {
        AutoScalingGroup group = findAutoScalingGroup();

        if (group == null || group.instances() == null || group.instances().isEmpty()) {
            return Map.of();
        }

        Set<String> activeInstanceIds = group.instances()
                .stream()
                .filter(this::isUsableAsgInstance)
                .map(Instance::instanceId)
                .collect(Collectors.toSet());

        if (activeInstanceIds.isEmpty()) {
            return Map.of();
        }

        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .instanceIds(activeInstanceIds)
                .build();

        Map<String, String> workers = new HashMap<>();

        ec2Client.describeInstances(request).reservations().forEach(reservation ->
                reservation.instances().forEach(instance -> {
                    String state = instance.state() == null || instance.state().name() == null
                            ? ""
                            : instance.state().name().toString();

                    if (!"running".equalsIgnoreCase(state)) {
                        return;
                    }

                    String address = "private".equals(addressMode)
                            ? instance.privateIpAddress()
                            : instance.publicIpAddress();

                    if (address == null || address.isBlank()) {
                        return;
                    }

                    String instanceId = instance.instanceId();
                    String baseUrl = "http://" + address + ":" + workerPort;

                    workers.put(instanceId, baseUrl);
                })
        );

        return workers;
    }

    public String getAutoScalingGroupName() {
        return autoScalingGroupName;
    }

    public String getAddressMode() {
        return addressMode;
    }

    public int getWorkerPort() {
        return workerPort;
    }

    @Override
    public void close() {
        autoScalingClient.close();
        ec2Client.close();
    }

    private AutoScalingGroup findAutoScalingGroup() {
        DescribeAutoScalingGroupsRequest request = DescribeAutoScalingGroupsRequest.builder()
                .autoScalingGroupNames(autoScalingGroupName)
                .build();

        return autoScalingClient.describeAutoScalingGroups(request)
                .autoScalingGroups()
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean isUsableAsgInstance(Instance instance) {
        if (instance == null || instance.instanceId() == null || instance.instanceId().isBlank()) {
            return false;
        }

        String lifecycleState = instance.lifecycleStateAsString();

        if (lifecycleState == null) {
            return false;
        }

        return "InService".equalsIgnoreCase(lifecycleState);
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

    private static String normalizeAddressMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return DEFAULT_ADDRESS_MODE;
        }

        String normalized = mode.trim().toLowerCase();

        if (!"public".equals(normalized) && !"private".equals(normalized)) {
            throw new IllegalArgumentException("CNV_WORKER_ADDRESS_MODE must be public or private");
        }

        return normalized;
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

            if (parsed <= 0 || parsed > 65535) {
                throw new IllegalArgumentException(name + " must be a valid port");
            }

            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + name + ": " + value, e);
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
}