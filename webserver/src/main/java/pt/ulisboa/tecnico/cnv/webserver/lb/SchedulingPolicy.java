package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.Collection;
import java.util.Optional;

/**
 * Interface for LB scheduling strategies.
 */
public interface SchedulingPolicy {

    Optional<WorkerState> chooseWorker(Collection<WorkerState> workers, long estimatedInstructions);
}