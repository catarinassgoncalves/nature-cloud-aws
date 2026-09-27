# Nature@Cloud — Application-Aware Cloud Computing on AWS

Cloud computing system for executing heterogeneous, CPU-intensive workloads on **Amazon Web Services (AWS)** using a hybrid architecture combining **EC2, AWS Lambda, DynamoDB, custom load balancing, and auto-scaling**.

The system estimates the computational cost of incoming requests through **bytecode-level instrumentation** and historical execution data, allowing scheduling and scaling decisions to account for the expected complexity of each request rather than relying only on infrastructure-level metrics.

## Project Overview

Nature@Cloud executes three computational workloads with different cost characteristics:

- **Julia-set fractal generation**
- **Gray-Scott reaction-diffusion simulation**
- **DNA sequence matching**

The system was designed to execute these workloads efficiently while handling heterogeneous request costs, workload bursts, worker failures, and changing infrastructure demand.

The final architecture combines:

- an EC2-based worker pool;
- a custom Java controller and load balancer;
- AWS Lambda as a fallback and overflow backend;
- bytecode-level workload instrumentation using Javassist;
- DynamoDB-backed execution metrics;
- dynamic request cost estimation;
- application-aware scheduling;
- custom EC2 auto-scaling;
- worker lifecycle management and fault tolerance.

---

## System Architecture

```text
                         ┌─────────────────┐
                         │     Client      │
                         └────────┬────────┘
                                  │
                                  ▼
                     ┌────────────────────────┐
                     │       Controller       │
                     │                        │
                     │  Cost Estimation       │
                     │  Load Balancing        │
                     │  Auto-Scaling          │
                     │  Worker Management     │
                     └───────┬────────┬───────┘
                             │        │
                   ┌─────────┘        └─────────┐
                   ▼                            ▼
          ┌─────────────────┐          ┌─────────────────┐
          │   EC2 Workers   │          │   AWS Lambda    │
          │                 │          │                 │
          │ Fractals        │          │ Fallback /      │
          │ Gray-Scott      │          │ Overflow        │
          │ DNA             │          │ Backend         │
          └────────┬────────┘          └─────────────────┘
                   │
                   │ Execution metrics
                   ▼
          ┌─────────────────┐
          │    DynamoDB     │
          │                 │
          │ Historical      │
          │ Request Metrics │
          └─────────────────┘
```

EC2 workers are the primary execution backend. AWS Lambda provides additional capacity when no EC2 worker is available, when predicted EC2 completion time becomes too high, or when EC2 forwarding fails.

---

## 1. Bytecode-Level Instrumentation

Execution time alone is not a reliable measure of request complexity because it is affected by concurrent load and infrastructure conditions.

To obtain an application-level measure of computational cost, the system uses **Javassist** to instrument the workload bytecode.

For each request, the system records:

- executed methods;
- executed basic blocks;
- executed bytecode instructions;
- execution time;
- workload and request parameters;
- execution status.

Because workers can process multiple requests concurrently, instrumentation counters are isolated per execution thread.

The resulting **instruction count** provides a common computational-cost representation across the three heterogeneous workloads.

---

## 2. Request Cost Estimation

Before scheduling a request, the controller estimates how computationally expensive it will be.

Workload-specific fallback models were derived from profiling data:

```text
Fractals   → image dimensions × iteration complexity
Gray-Scott → simulation grid size × number of iterations
DNA        → sequence lengths + matching parameters
```

The fitted models achieved:

| Workload | R² |
|---|---:|
| **Fractals** | **1.000** |
| **Gray-Scott** | **1.000** |
| DNA | 0.986 |

At runtime, these static models are combined with historical measurements stored in **DynamoDB**.

The estimator follows a hierarchical strategy:

```text
Exact historical match available?
        │
        ├── Yes → Average measured instruction count
        │
        └── No
             │
             ▼
Historical data for the workload available?
        │
        ├── Yes → Estimate from recent workload measurements
        │
        └── No  → Static workload-specific model
```

This allows the system to provide estimates for previously unseen requests while adapting to observed executions over time.

---

## 3. Application-Aware Load Balancing

Instead of assigning requests according only to the number of active connections, the custom scheduler considers the **predicted computational backlog** of each EC2 worker.

For an incoming request, the controller considers:

- estimated instruction cost of the request;
- instructions already assigned to each worker;
- estimated processing speed of each worker;
- number of in-flight requests.

The request is assigned to the worker with the lowest predicted completion score.

Worker state is updated immediately after assignment so that concurrent scheduling decisions account for newly assigned work.

Processing-speed estimates are also updated dynamically from completed requests using an exponential moving average.

---

## 4. Lambda Fallback & Overflow

**AWS Lambda** acts as a secondary execution backend.

Lambda can be used when:

- no active EC2 worker is available;
- predicted EC2 completion time exceeds the overflow threshold;
- forwarding to EC2 fails.

EC2 remains the preferred backend during normal operation, while Lambda absorbs temporary bursts, startup delays, and worker failures.

Requests above a configurable estimated instruction threshold are prevented from being sent to Lambda, limiting serverless execution of very expensive workloads.

---

## 5. Custom Auto-Scaling

A custom Java auto-scaler adjusts the size of the EC2 Auto Scaling Group according to the **estimated instruction backlog per active worker**.

The deployed configuration used:

| Parameter | Value |
|---|---:|
| Minimum workers | 1 |
| Maximum workers | 3 |
| Control interval | 15 s |
| Scale-out threshold | 2.5 × 10⁹ instructions |
| Scale-in threshold | 2.5 × 10⁸ instructions |
| Consecutive intervals for scale-out | 2 |
| Consecutive intervals for scale-in | 4 |
| Cooldown | 120 s |

The difference between the scale-out and scale-in thresholds introduces **hysteresis**, reducing oscillation caused by temporary load changes.

During scale-in, workers first enter a `DRAINING` state. They stop receiving new requests and are terminated only after their current requests have completed.

---

## 6. Fault Tolerance

The controller maintains an explicit lifecycle for each EC2 worker:

```text
ACTIVE → DRAINING
   │
   └────→ UNHEALTHY
```

If a worker becomes unreachable, times out, crashes, or returns a server-side error:

1. its assigned estimated load is removed;
2. the worker is marked as `UNHEALTHY`;
3. it is excluded from subsequent scheduling;
4. the request is retried on another EC2 worker;
5. Lambda can be used as a final fallback when eligible.

The controller also periodically synchronizes its worker registry with the AWS Auto Scaling Group.

---

## Evaluation

### Runtime Cost Estimation

Runtime telemetry showed different levels of prediction accuracy across workloads:

| Workload | Records | Median APE | Predictions within 10% error |
|---|---:|---:|---:|
| DNA | 7 | 100.0% | 14.3% |
| Fractals | 19 | 72.0% | 5.3% |
| **Gray-Scott** | **75** | **0.0%** | **96.0%** |

Gray-Scott showed highly stable cost prediction, while Fractals and DNA were more sensitive to parameter variation and limited historical coverage.

The results highlight an important limitation of application-aware prediction: estimator quality depends strongly on the quantity and diversity of previously observed workload configurations.

### Auto-Scaling Behaviour

The custom auto-scaler was also evaluated under a workload burst.

```text
Idle
  │
  │ 1 active worker
  ▼
Workload burst
  │
  │ Estimated load rises to 8.59 × 10¹¹
  ▼
Scale-out triggered
  │
  │ Desired capacity: 1 → 2
  ▼
Second worker becomes active
  │
  ▼
Average estimated load per worker
8.59 × 10¹¹ → 4.29 × 10¹¹
```

The observed execution confirmed the intended control-loop behaviour:

- minimum capacity was maintained while idle;
- sustained high load triggered scale-out;
- newly available workers were incorporated into scheduling;
- workers were drained before termination during scale-in.

---

## Key Findings

- **Bytecode instruction counts provide an application-level cost metric** that can be compared across heterogeneous workloads.
- Static profiling models captured the workload cost structure strongly, reaching **R² = 1.000** for Fractals and Gray-Scott and **0.986** for DNA.
- Combining static models with **DynamoDB-backed execution history** allows cost estimates to adapt as requests are processed.
- Application-aware scheduling considers both request complexity and existing worker backlog instead of relying solely on request counts.
- **AWS Lambda** provides additional resilience and burst capacity without replacing EC2 as the primary backend.
- The custom auto-scaler successfully reacted to sustained application-level load and used worker draining to avoid interrupting active requests.
- Runtime experiments also exposed limitations in cost estimation for workloads with limited or heterogeneous historical data.

---

## Repository Structure

```text
nature-cloud-aws/
├── dna/             # DNA sequence matching workload
├── fractals/        # Julia-set fractal generation
├── grayscott/       # Gray-Scott reaction-diffusion simulation
├── javassist/       # Bytecode instrumentation and instruction counting
├── webserver/       # Worker server, controller and cloud orchestration logic
├── scripts/         # AWS deployment and operational scripts
├── report/
│   └── project_report.pdf
├── pom.xml          # Parent Maven configuration
├── README.txt       # Original project instructions/documentation
└── README.md
```

The main cloud orchestration components are implemented inside `webserver`, including:

```text
webserver/src/main/java/.../webserver/
├── cost/            # Request cost estimation
├── lb/              # Load balancing, worker management and Lambda integration
├── as/              # Auto-scaling policies
└── metrics/         # DynamoDB and in-memory metrics repositories
```

---

## Technologies

**Java · AWS · EC2 · AWS Lambda · DynamoDB · EC2 Auto Scaling · Javassist · Maven · Bash**

Key concepts:

- Cloud Computing
- Application-Aware Scheduling
- Load Balancing
- Auto-Scaling
- Serverless Computing
- Runtime Instrumentation
- Fault Tolerance
- Distributed Systems
- Infrastructure Automation

---

## Project Context

Developed as part of the **Cloud Computing and Virtualization** course at **Instituto Superior Técnico, University of Lisbon**.

The project was developed collaboratively by:

- Catarina Gonçalves
- Vasco Fernandes
- Maria Rodrigues

The complete architecture, implementation details, evaluation, limitations, and experimental results are available in the [project report](report/project_report.pdf).