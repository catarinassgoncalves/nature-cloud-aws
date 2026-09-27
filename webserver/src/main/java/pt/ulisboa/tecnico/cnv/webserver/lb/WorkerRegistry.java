package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import pt.ulisboa.tecnico.cnv.webserver.as.ClusterSnapshot;

public final class WorkerRegistry {

    private final ConcurrentHashMap<String, WorkerState> workers = new ConcurrentHashMap<>();

    public WorkerState registerWorker(String workerId, String baseUrl) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(baseUrl, "baseUrl");

        return workers.compute(workerId, (id, existing) -> {
            if (existing != null) {
                existing.setBaseUrl(baseUrl);

                if (existing.isUnhealthy()) {
                    existing.markActive();
                }

                return existing;
            }

            return new WorkerState(workerId, baseUrl);
        });
    }

    public WorkerState registerWorker(String workerId, String baseUrl, double initialInstructionsPerSecond) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(baseUrl, "baseUrl");

        return workers.compute(workerId, (id, existing) -> {
            if (existing != null) {
                existing.setBaseUrl(baseUrl);

                if (existing.isUnhealthy()) {
                    existing.markActive();
                }

                return existing;
            }

            return new WorkerState(workerId, baseUrl, initialInstructionsPerSecond);
        });
    }

    public synchronized void replaceWorkers(Map<String, String> discoveredWorkers) {
        if (discoveredWorkers == null) {
            return;
        }

        for (Map.Entry<String, String> entry : discoveredWorkers.entrySet()) {
            String workerId = entry.getKey();
            String baseUrl = entry.getValue();

            if (workerId == null || workerId.isBlank() || baseUrl == null || baseUrl.isBlank()) {
                continue;
            }

            registerWorker(workerId, baseUrl);
        }

        for (String existingWorkerId : new ArrayList<>(workers.keySet())) {
            if (!discoveredWorkers.containsKey(existingWorkerId)) {
                WorkerState worker = workers.get(existingWorkerId);

                if (worker == null) {
                    continue;
                }

                if (worker.getInFlightRequests() > 0) {
                    worker.markUnhealthy();
                } else {
                    workers.remove(existingWorkerId);
                }
            }
        }
    }

    public void removeWorker(String workerId) {
        workers.remove(workerId);
    }

    public WorkerState getWorker(String workerId) {
        return workers.get(workerId);
    }

    public List<WorkerState> getAllWorkers() {
        return Collections.unmodifiableList(new ArrayList<>(workers.values()));
    }

    public List<WorkerState> getHealthyWorkers() {
        return getActiveWorkers();
    }

    public List<WorkerState> getActiveWorkers() {
        List<WorkerState> active = new ArrayList<>();

        for (WorkerState worker : workers.values()) {
            if (worker.isActive()) {
                active.add(worker);
            }
        }

        return Collections.unmodifiableList(active);
    }

    public List<WorkerState> getDrainingWorkers() {
        List<WorkerState> draining = new ArrayList<>();

        for (WorkerState worker : workers.values()) {
            if (worker.isDraining()) {
                draining.add(worker);
            }
        }

        return Collections.unmodifiableList(draining);
    }

    public void markHealthy(String workerId) {
        WorkerState worker = workers.get(workerId);
        if (worker != null) {
            worker.markActive();
        }
    }

    public void markUnhealthy(String workerId) {
        WorkerState worker = workers.get(workerId);
        if (worker != null) {
            worker.markUnhealthy();
        }
    }

    public void markDraining(String workerId) {
        WorkerState worker = workers.get(workerId);
        if (worker != null) {
            worker.markDraining();
        }
    }

    public void assignRequest(String workerId, long estimatedInstructions) {
        WorkerState worker = requireWorker(workerId);
        worker.assignRequest(estimatedInstructions);
    }

    public void completeRequest(
            String workerId,
            long estimatedInstructions,
            long actualInstructions,
            long elapsedNanos
    ) {
        WorkerState worker = requireWorker(workerId);
        worker.completeRequest(estimatedInstructions, actualInstructions, elapsedNanos);
    }

    public void failAssignedRequest(String workerId, long estimatedInstructions) {
        WorkerState worker = requireWorker(workerId);
        worker.failAssignedRequest(estimatedInstructions);
    }

    public int getActiveWorkerCount() {
        return getActiveWorkers().size();
    }

    public long getTotalActiveEstimatedInstructions() {
        long total = 0L;

        for (WorkerState worker : workers.values()) {
            if (worker.isActive()) {
                total += worker.getCurrentEstimatedInstructions();
            }
        }

        return total;
    }

    public WorkerState findBestScaleInCandidate() {
        return workers.values()
                .stream()
                .filter(WorkerState::isActive)
                .filter(worker -> worker.getInFlightRequests() == 0)
                .min(Comparator.comparingLong(WorkerState::getCurrentEstimatedInstructions))
                .orElse(null);
    }

    public ClusterSnapshot snapshot() {
        Collection<WorkerState> currentWorkers = workers.values();
        return ClusterSnapshot.from(currentWorkers);
    }

    private WorkerState requireWorker(String workerId) {
        WorkerState worker = workers.get(workerId);

        if (worker == null) {
            throw new IllegalArgumentException("Unknown worker: " + workerId);
        }

        return worker;
    }
}