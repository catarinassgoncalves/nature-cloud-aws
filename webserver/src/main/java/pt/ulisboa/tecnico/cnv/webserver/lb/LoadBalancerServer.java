package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.webserver.RootHandler;

public class LoadBalancerServer {

    private static final int DEFAULT_LB_PORT = 8080;
    private static final String DEFAULT_WORKERS = "local-worker=http://localhost:8000";

    public static void main(String[] args) throws Exception {
        int port = getIntEnv("CNV_LB_PORT", DEFAULT_LB_PORT);

        WorkerRegistry workerRegistry = new WorkerRegistry();
        AwsWorkerDiscovery awsWorkerDiscovery = null;
        JavaAutoScaler javaAutoScaler = null;
        ScheduledExecutorService discoveryExecutor = null;

        if (isAwsDiscoveryEnabled()) {
            awsWorkerDiscovery = new AwsWorkerDiscovery();
            refreshAwsWorkers(workerRegistry, awsWorkerDiscovery);

            discoveryExecutor = Executors.newSingleThreadScheduledExecutor();
            AwsWorkerDiscovery finalDiscovery = awsWorkerDiscovery;
            discoveryExecutor.scheduleAtFixedRate(
                    () -> refreshAwsWorkers(workerRegistry, finalDiscovery),
                    10,
                    getIntEnv("CNV_WORKER_DISCOVERY_INTERVAL_SECONDS", 15),
                    TimeUnit.SECONDS
            );
        } else {
            registerWorkersFromEnvironment(workerRegistry);
        }

        if (isJavaAutoScalerEnabled()) {
            javaAutoScaler = new JavaAutoScaler(workerRegistry);
            javaAutoScaler.start();
        }

        SchedulingPolicy schedulingPolicy = new LeastFinishTimeScheduler();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new ForwardingHandler(
                "fractals",
                workerRegistry,
                schedulingPolicy
        ));
        server.createContext("/grayscott", new ForwardingHandler(
                "grayscott",
                workerRegistry,
                schedulingPolicy
        ));
        server.createContext("/dna", new ForwardingHandler(
                "dna",
                workerRegistry,
                schedulingPolicy
        ));

        AwsWorkerDiscovery discoveryToClose = awsWorkerDiscovery;
        JavaAutoScaler autoScalerToClose = javaAutoScaler;
        ScheduledExecutorService executorToStop = discoveryExecutor;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (executorToStop != null) {
                executorToStop.shutdownNow();
            }
            if (autoScalerToClose != null) {
                autoScalerToClose.close();
            }
            if (discoveryToClose != null) {
                discoveryToClose.close();
            }
        }));

        server.start();

        System.out.println("[LB] Java Load Balancer running on http://127.0.0.1:" + port);

        if (isAwsDiscoveryEnabled()) {
            System.out.println("[LB] AWS ASG worker discovery enabled.");
        }

        if (isJavaAutoScalerEnabled()) {
            System.out.println("[AS] Java AutoScaler is enabled.");
        }

        printWorkers(workerRegistry);
    }

    private static boolean isAwsDiscoveryEnabled() {
        String enabled = System.getenv("CNV_WORKER_DISCOVERY");

        return enabled != null && enabled.equalsIgnoreCase("asg");
    }

    private static boolean isJavaAutoScalerEnabled() {
        String enabled = System.getenv("CNV_AS_ENABLED");

        return enabled != null && enabled.equalsIgnoreCase("true");
    }

    private static void refreshAwsWorkers(WorkerRegistry workerRegistry, AwsWorkerDiscovery discovery) {
        try {
            Map<String, String> workers = discovery.discoverWorkers();

            workerRegistry.replaceWorkers(workers);

            System.out.printf(
                    "[LB] AWS discovery refreshed ASG=%s mode=%s workers=%d%n",
                    discovery.getAutoScalingGroupName(),
                    discovery.getAddressMode(),
                    workers.size()
            );

            for (Map.Entry<String, String> worker : workers.entrySet()) {
                System.out.printf("[LB]   %s -> %s%n", worker.getKey(), worker.getValue());
            }
        } catch (RuntimeException e) {
            System.err.println("[LB] AWS worker discovery failed: " + e.getMessage());
        }
    }

    private static void registerWorkersFromEnvironment(WorkerRegistry workerRegistry) {
        String workers = System.getenv("CNV_WORKERS");

        if (workers == null || workers.trim().isEmpty()) {
            workers = DEFAULT_WORKERS;
        }

        String[] entries = workers.split(",");

        for (String entry : entries) {
            String trimmed = entry.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            String workerId;
            String baseUrl;

            int equalsIndex = trimmed.indexOf('=');

            if (equalsIndex >= 0) {
                workerId = trimmed.substring(0, equalsIndex).trim();
                baseUrl = trimmed.substring(equalsIndex + 1).trim();
            } else {
                baseUrl = trimmed;
                workerId = "worker-" + Math.abs(baseUrl.hashCode());
            }

            if (workerId.isEmpty() || baseUrl.isEmpty()) {
                throw new IllegalArgumentException("Invalid worker entry in CNV_WORKERS: " + entry);
            }

            workerRegistry.registerWorker(workerId, baseUrl);
        }

        if (workerRegistry.getAllWorkers().isEmpty()) {
            throw new IllegalStateException("No workers registered. Set CNV_WORKERS or enable ASG discovery.");
        }
    }

    private static void printWorkers(WorkerRegistry workerRegistry) {
        System.out.println("[LB] Registered workers:");
        for (WorkerState worker : workerRegistry.getAllWorkers()) {
            System.out.println(
                    "[LB]   " + worker.getWorkerId()
                            + " -> " + worker.getBaseUrl()
                            + " lifecycle=" + worker.getLifecycle()
            );
        }
    }

    private static int getIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.trim());

            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }

            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + name + ": " + value, e);
        }
    }
}