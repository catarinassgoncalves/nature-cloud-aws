package pt.ulisboa.tecnico.cnv.webserver.cost;

public interface CostEstimator<T> {
    String workloadName();
    long estimate(T input);
}