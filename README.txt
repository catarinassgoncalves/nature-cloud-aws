Nature@Cloud Final Code Delivery- Group 26

This zip contains the final Nature@Cloud implementation, including the original workload code, the instrumentation code used to collect request metrics, the Java webserver, the Java load balancer/autoscaler, AWS deployment scripts, Lambda deployment scripts, and relevant configuration examples.

Repository organization:

- fractals/
  Contains the Fractals workload implementation and handler code.

- grayscott/
  Contains the Gray-Scott workload implementation and handler code.

- dna/
  Contains the DNA sequence matching workload implementation and handler code.

- javassist/
  Contains the bytecode instrumentation code used to collect request-level metrics, including instruction count, basic block count, method count, and thread/request information.

- webserver/
  Contains the main Java webserver and the final system logic. Relevant components include:

  - measured workload handlers that wrap the original workloads and record metrics;
  - RequestMetricsRecorder, which records request metrics;
  - DynamoDB-backed metrics storage;
  - dynamic cost estimators;
  - Java load balancer logic;
  - Java autoscaler logic;
  - worker discovery through the AWS Auto Scaling Group;
  - Lambda backend client and overflow routing support.

- scripts/
  Contains deployment and management scripts for AWS. Important scripts include:

  - config.sh.example: example configuration file with required environment variables;
  - setup-iam.sh: creates/updates IAM roles and instance profiles for workers and the controller;
  - create-image.sh: creates the AMI used by worker/controller instances;
  - deregister-image.sh: deregisters the current AMI;
  - launch-deployment-template.sh: creates the worker Auto Scaling Group and launch template;
  - terminate-deployment-template.sh: terminates the worker deployment;
  - launch-controller.sh: launches the EC2 controller running the Java Load Balancer and Java Auto Scaler;
  - terminate-controller.sh: terminates the controller instance;
  - deploy-lambda.sh: deploys the Lambda functions for the supported workloads;
  - delete-lambda.sh: deletes the deployed Lambda functions.

Main architecture:

The final system uses a dedicated EC2 controller instance. This controller runs the Java Load Balancer and Java Auto Scaler. Worker instances run the instrumented Nature@Cloud webserver and execute the actual workloads. The controller discovers active workers from the Auto Scaling Group, estimates request complexity using historical metrics stored in DynamoDB, and routes each request to an EC2 worker or, when EC2 is overloaded, to Lambda as overflow capacity.

Metrics:

Request metrics are collected using bytecode instrumentation and stored in DynamoDB. Recorded fields include workload name, query parameters, worker identifier, estimated instruction count, actual instruction count, basic block count, method count, elapsed execution time, completion status, and error information when applicable.

The actualInstructions field is the measured request complexity. The estimatedInstructions field is the load balancer’s prediction before execution, propagated to the worker when requests go through the controller.

Autoscaling:

The Java Auto Scaler runs in the controller and uses estimated in-flight instruction load to make scaling decisions. It avoids relying only on instantaneous CPU spikes. Scale-out happens when average estimated load per active worker remains high. Scale-in uses worker draining: a worker selected for termination stops receiving new requests, finishes its current in-flight requests, and is only then terminated.

Lambda:

Lambda functions are provided for the supported workloads. The load balancer prefers EC2 execution, but can invoke Lambda when EC2 predicted completion time is too high or when EC2 recovery/fallback is needed. Lambda deployment is handled through scripts/deploy-lambda.sh.

Configuration:

The included scripts/config.sh.example documents the required variables and should be copied/edited before deployment in order to have a proper config.sh file.

Notes:

CloudWatch monitoring is enabled for EC2 instances, but scaling decisions are made by the Java Auto Scaler to avoid conflicts and to support safe scale-in with draining.
