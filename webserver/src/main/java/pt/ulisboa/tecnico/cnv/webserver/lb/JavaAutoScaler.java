package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.SetDesiredCapacityRequest;
import software.amazon.awssdk.services.autoscaling.model.TerminateInstanceInAutoScalingGroupRequest;

public final class JavaAutoScaler implements AutoCloseable {

    private static final String DEFAULT_ASG_NAME = "CNV-NatureCloud-ASG";

    private final WorkerRegistry workerRegistry;
    private final AutoScalingClient autoScalingClient;
    private final ScheduledExecutorService executor;

    private final String autoScalingGroupName;
    private final int minWorkers;
    private final int maxWorkers;
    private final int intervalSeconds;
    private final int scaleOutConsecutiveIntervals;
    private final int scaleInConsecutiveIntervals;
    private final long cooldownMillis;
    private final long highAverageLoadThreshold;
    private final long lowAverageLoadThreshold;

    private int highLoadIntervals;
    private int lowLoadIntervals;
    private long lastScalingActionMillis;

    public JavaAutoScaler(WorkerRegistry workerRegistry) {
        this.workerRegistry = workerRegistry;
        this.autoScalingGroupName = envOrDefault("CNV_ASG_NAME", DEFAULT_ASG_NAME);
        this.minWorkers = intEnvOrDefault("CNV_AS_MIN_WORKERS", 1);
        this.maxWorkers = intEnvOrDefault("CNV_AS_MAX_WORKERS", 3);
        this.intervalSeconds = intEnvOrDefault("CNV_AS_INTERVAL_SECONDS", 15);
        this.scaleOutConsecutiveIntervals = intEnvOrDefault("CNV_AS_SCALE_OUT_CONSECUTIVE", 2);
        this.scaleInConsecutiveIntervals = intEnvOrDefault("CNV_AS_SCALE_IN_CONSECUTIVE", 4);
        this.cooldownMillis = 1000L * intEnvOrDefault("CNV_AS_COOLDOWN_SECONDS", 120);
        this.highAverageLoadThreshold = longEnvOrDefault("CNV_AS_HIGH_AVG_LOAD", 2_500_000_000L);
        this.lowAverageLoadThreshold = longEnvOrDefault("CNV_AS_LOW_AVG_LOAD", 250_000_000L);

        this.autoScalingClient = AutoScalingClient.builder()
                .region(resolveRegion())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.highLoadIntervals = 0;
        this.lowLoadIntervals = 0;
        this.lastScalingActionMillis = 0L;
    }

    public void start() {
        System.out.printf(
                "[AS] Java AutoScaler enabled ASG=%s min=%d max=%d interval=%ds highAvgLoad=%d lowAvgLoad=%d%n",
                autoScalingGroupName,
                minWorkers,
                maxWorkers,
                intervalSeconds,
                highAverageLoadThreshold,
                lowAverageLoadThreshold
        );

        executor.scheduleAtFixedRate(
                this::safeTick,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            System.err.println("[AS] tick failed: " + e.getMessage());
        }
    }

    private void tick() {
        processDrainingWorkers();

        AutoScalingGroup group = describeAutoScalingGroup();

        if (group == null) {
            System.err.println("[AS] ASG not found: " + autoScalingGroupName);
            return;
        }

        int desiredCapacity = group.desiredCapacity();
        int activeWorkers = workerRegistry.getActiveWorkerCount();
        long totalActiveLoad = workerRegistry.getTotalActiveEstimatedInstructions();
        long averageActiveLoad = activeWorkers == 0 ? 0L : totalActiveLoad / activeWorkers;

        System.out.printf(
                "[AS] status desired=%d active=%d totalLoad=%d avgLoad=%d highIntervals=%d lowIntervals=%d%n",
                desiredCapacity,
                activeWorkers,
                totalActiveLoad,
                averageActiveLoad,
                highLoadIntervals,
                lowLoadIntervals
        );

        if (inCooldown()) {
            return;
        }

        if (activeWorkers == 0 && desiredCapacity < minWorkers) {
            setDesiredCapacity(minWorkers);
            resetCounters();
            return;
        }

        if (averageActiveLoad >= highAverageLoadThreshold) {
            highLoadIntervals++;
            lowLoadIntervals = 0;
        } else if (averageActiveLoad <= lowAverageLoadThreshold) {
            lowLoadIntervals++;
            highLoadIntervals = 0;
        } else {
            resetCounters();
        }

        boolean pendingCapacityAlreadyRequested = desiredCapacity > activeWorkers;

        if (
                highLoadIntervals >= scaleOutConsecutiveIntervals
                        && desiredCapacity < maxWorkers
                        && !pendingCapacityAlreadyRequested
        ) {
            int newDesiredCapacity = Math.min(maxWorkers, desiredCapacity + 1);

            System.out.printf(
                    "[AS] scaling out desired=%d -> %d avgLoad=%d%n",
                    desiredCapacity,
                    newDesiredCapacity,
                    averageActiveLoad
            );

            setDesiredCapacity(newDesiredCapacity);
            resetCounters();
            return;
        }

        if (
                lowLoadIntervals >= scaleInConsecutiveIntervals
                        && desiredCapacity > minWorkers
                        && activeWorkers > minWorkers
        ) {
            WorkerState candidate = workerRegistry.findBestScaleInCandidate();

            if (candidate == null) {
                System.out.println("[AS] scale-in wanted, but no idle ACTIVE worker is available.");
                return;
            }

            candidate.markDraining();

            System.out.printf(
                    "[AS] marked worker as DRAINING worker=%s inFlight=%d load=%d%n",
                    candidate.getWorkerId(),
                    candidate.getInFlightRequests(),
                    candidate.getCurrentEstimatedInstructions()
            );

            resetCounters();
        }
    }

    private void processDrainingWorkers() {
        List<WorkerState> drainingWorkers = workerRegistry.getDrainingWorkers();

        for (WorkerState worker : drainingWorkers) {
            if (worker.getInFlightRequests() > 0) {
                System.out.printf(
                        "[AS] waiting for DRAINING worker=%s inFlight=%d load=%d%n",
                        worker.getWorkerId(),
                        worker.getInFlightRequests(),
                        worker.getCurrentEstimatedInstructions()
                );
                continue;
            }

            if (!isEc2InstanceId(worker.getWorkerId())) {
                System.out.printf(
                        "[AS] removing non-EC2 DRAINING worker=%s%n",
                        worker.getWorkerId()
                );
                workerRegistry.removeWorker(worker.getWorkerId());
                continue;
            }

            AutoScalingGroup group = describeAutoScalingGroup();

            if (group == null || group.desiredCapacity() <= minWorkers) {
                System.out.printf(
                        "[AS] cannot terminate DRAINING worker=%s because desired capacity is already at min.%n",
                        worker.getWorkerId()
                );
                worker.markActive();
                continue;
            }

            System.out.printf(
                    "[AS] terminating drained worker=%s and decrementing desired capacity%n",
                    worker.getWorkerId()
            );

            terminateWorker(worker.getWorkerId());
            workerRegistry.removeWorker(worker.getWorkerId());
            recordScalingAction();
        }
    }

    private AutoScalingGroup describeAutoScalingGroup() {
        DescribeAutoScalingGroupsRequest request = DescribeAutoScalingGroupsRequest.builder()
                .autoScalingGroupNames(autoScalingGroupName)
                .build();

        return autoScalingClient.describeAutoScalingGroups(request)
                .autoScalingGroups()
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void setDesiredCapacity(int desiredCapacity) {
        SetDesiredCapacityRequest request = SetDesiredCapacityRequest.builder()
                .autoScalingGroupName(autoScalingGroupName)
                .desiredCapacity(desiredCapacity)
                .honorCooldown(false)
                .build();

        autoScalingClient.setDesiredCapacity(request);
        recordScalingAction();
    }

    private void terminateWorker(String instanceId) {
        TerminateInstanceInAutoScalingGroupRequest request =
                TerminateInstanceInAutoScalingGroupRequest.builder()
                        .instanceId(instanceId)
                        .shouldDecrementDesiredCapacity(true)
                        .build();

        autoScalingClient.terminateInstanceInAutoScalingGroup(request);
    }

    private boolean inCooldown() {
        long elapsed = System.currentTimeMillis() - lastScalingActionMillis;
        return elapsed < cooldownMillis;
    }

    private void recordScalingAction() {
        lastScalingActionMillis = System.currentTimeMillis();
    }

    private void resetCounters() {
        highLoadIntervals = 0;
        lowLoadIntervals = 0;
    }

    private static boolean isEc2InstanceId(String workerId) {
        return workerId != null && workerId.startsWith("i-");
    }

    @Override
    public void close() {
        executor.shutdownNow();
        autoScalingClient.close();
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
}