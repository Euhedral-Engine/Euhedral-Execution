# Euhedral Training Architecture & Component Diagrams

This document maps out the component architecture, execution pipeline, calibration flow, candidate optimization loop, and model training workflow for the `euhedral-training` subsystem and its integration with the core Euhedral engine.

---

## 1. System Module Topology & Component Boundaries

The overall dependency graph between lower-level core components and higher-level training pipelines.

```mermaid
graph TD
    %% Node Definitions
    CORE["euhedral-core<br/>(Control Plane & Routing)"]

    subgraph TrainingSubsystem["euhedral-training"]
        SCHED["Candidate Scheduler & Budget Allocator"]
        BENCH["Benchmark Runner & Ingestion"]
        CALIB["Log-Space Calibrator & Hierarchical Aggregator"]
        MODEL["Scenario-Conditioned Neural Model (TensorFlow)"]
        OPT["CMA-ES & Score-Band Optimizer"]
        PKG["Training Run Packager & Checkpointing"]
    end

    %% Relationships
    BENCH --> CORE
    SCHED --> BENCH
    BENCH --> CALIB
    CALIB --> MODEL
    MODEL --> OPT
    OPT --> SCHED
    MODEL --> PKG

    %% Styling
    classDef violet fill:#94167f,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
    classDef amber fill:#f9ac53,stroke:#94167f,stroke-width:2px,color:#050d21,font-weight:bold;
    classDef blue fill:#153cb4,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
    classDef navy fill:#0b1942,stroke:#f9ac53,stroke-width:2px,color:#ffffff;

    class HASH,DATA,HW,CORE violet;
    class SCHED,OPT amber;
    class BENCH,CALIB blue;
    class MODEL,PKG,SPRING,REACTOR navy;
```

---

## 2. Closed-Loop Optimization Pipeline

The complete iteration workflow managed by `ClosedLoopRunner` across scheduling, native execution, log-space calibration, neural surrogate training, candidate search, and checkpoint packaging.

```mermaid
flowchart TD
    START(["Start Closed-Loop Iteration"]) --> CONF["Load ClosedLoopConfig"]
    CONF --> COLD{"Cold-Start<br/>Initial State?"}

    COLD -- Yes --> BOOT["Generate Sobol / Import Initial Bootstrap Policies"]
    COLD -- No --> RECON["Reconcile Carry-Forward Queue & Active Scenarios"]

    BOOT --> SCHED["Schedule Candidate Cohort across Required Scenarios"]
    RECON --> SCHED

    SCHED --> EXEC["Benchmark Execution (BenchmarkRunner)"]
    EXEC --> OBS["Record Observation Bundles (CSV / Binary)"]

    OBS --> MERGE["DataMerger: Aggregate & Align Multi-Environment Runs"]
    MERGE --> CALIB["Log-Space Calibration & Anchor Policy Normalization"]

    CALIB --> TRAIN["Train Neural Surrogate Model (ScenarioConditionedModel)"]
    TRAIN --> EVAL["Evaluate Validation Metrics & Loss Gates"]

    EVAL -- Model Accepted --> OPT["CMA-ES Evolutionary Candidate Search"]
    EVAL -- Model Rejected --> FALLBACK["Cold-Start Fallback Schedule Generation"]

    OPT --> CHECK["Emit Iteration Checkpoint & Package Artifacts"]
    FALLBACK --> CHECK

    CHECK --> DONE{"More Iterations<br/>Remaining?"}
    DONE -- Yes --> RECON
    DONE -- No --> END(["Export Final Package Manifest"])

    %% Styling
    classDef violet fill:#94167f,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
    classDef amber fill:#f9ac53,stroke:#94167f,stroke-width:2px,color:#050d21,font-weight:bold;
    classDef blue fill:#153cb4,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
    classDef navy fill:#0b1942,stroke:#d8a4f0,stroke-width:2px,color:#ffffff;

    class START,END,DONE,COLD navy;
    class SCHED,OPT,FALLBACK amber;
    class EXEC,OBS,MERGE,CALIB blue;
    class CONF,BOOT,RECON,TRAIN,EVAL,CHECK violet;
```

---

## 3. Data Calibration & Multi-Environment Aggregation

How observation bundles from distinct machine topology scenarios are merged, aligned in log-space, and converted into normalized training targets.

```mermaid
graph LR
    subgraph RawObservations["Raw Physical Benchmark Runs"]
        OB1["Scenario s1-host-a-src1-cores32<br/>Observation CSV"]
        OB2["Scenario s1-host-a-src32-cores32<br/>Observation CSV"]
        OB3["Scenario s1-host-b-src8-cores16<br/>Observation CSV"]
    end

    subgraph AnchorCalibration["Log-Space Alignment & Calibration"]
        ANCHOR["Anchor Selection Engine<br/>(Fixed + Weak Anchors)"]
        CALIB["Run Calibrator<br/>(Log-Space Shift & Scaling)"]
        QUALITY["Quality Ranker & Ratio Normalizer"]
    end

    subgraph DatasetPreparation["Surrogate Model Input Assembly"]
        SPLIT["Policy-Grouped Data Splitter<br/>(Train / Val / Test / Ablation)"]
        ENCODER["Scenario Feature Encoder<br/>(Topology Embeddings)"]
        TARGETS["Ordinal & Continuous Target Converter"]
    end

    OB1 --> ANCHOR
    OB2 --> ANCHOR
    OB3 --> ANCHOR

    ANCHOR --> CALIB
    CALIB --> QUALITY
    QUALITY --> SPLIT

    SPLIT --> ENCODER
    ENCODER --> TARGETS
    TARGETS --> TENSOR["TensorFlow Training Dataset"]

    %% Styling
    classDef violet fill:#94167f,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
    classDef amber fill:#f9ac53,stroke:#94167f,stroke-width:2px,color:#050d21,font-weight:bold;
    classDef blue fill:#153cb4,stroke:#f9ac53,stroke-width:2px,color:#ffffff;

    class OB1,OB2,OB3 blue;
    class ANCHOR,CALIB,QUALITY amber;
    class SPLIT,ENCODER,TARGETS,TENSOR violet;
```

---

## 4. Candidate Generation & CMA-ES Evolutionary Loop

The inner optimization loop that uses the surrogate model to search for candidate runtime policy vectors.

```mermaid
stateDiagram-v2
    [*] --> BudgetAllocation: Initialize Iteration Budget

    state BudgetAllocation {
        Exploration: Exploration Allocation (Sobol)
        CarryForward: Carry-Forward Queue Allocation
        Revalidation: Leader Revalidation Allocation
        Audit: Scenario Audit Allocation
    }

    BudgetAllocator --> SurrogatePrediction: Query Candidate Pool

    state SurrogatePrediction {
        EvaluateModel: Predict Quality across Topology Scenarios
        ComputeUncertainty: Estimate Epistemic Variance & Entropy
        ScoreBand: Categorize into Score-Bands
    }

    SurrogatePrediction --> EvolutionarySearch: Feed Seeds to CMA-ES

    state EvolutionarySearch {
        CmaEsStep: Mutate 28-D Policy Weight Vectors
        RankCandidates: Rank by Expected Multi-Scenario Performance
        SelectLeaders: Filter Robust Candidates
    }

    EvolutionarySearch --> ScheduledCohort: Finalize Cohort Schedule
    ScheduledCohort --> [*]: Dispatch to BenchmarkRunner

    %% Custom Styling
    classDef violet fill:#94167f,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
    classDef amber fill:#f9ac53,stroke:#94167f,stroke-width:2px,color:#050d21,font-weight:bold;
    classDef blue fill:#153cb4,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
```

---

## 5. Neural Surrogate Model Architecture

The multi-head TensorFlow architecture mapping 28 policy weights + scenario topology features to predicted performance distributions.

```mermaid
graph TD
    subgraph Inputs["Input Features"]
        PWEIGHTS["Policy Vector (28 Continuous Weights)"]
        TOPOLOGY["Scenario Features (Core Count, Source Count, Ratio)"]
    end

    subgraph Backbone["Shared Feature Backbone"]
        DENSE1["Dense Layer (64 Hidden Units, Swish)"]
        NORM1["Layer Normalization"]
        DENSE2["Dense Layer (64 Hidden Units, Swish)"]
        NORM2["Layer Normalization"]
    end

    subgraph Heads["Prediction Task Heads"]
        QUALITY_HEAD["Quality Prediction Head (Continuous Output)"]
        VARIANCE_HEAD["Uncertainty Head (Epistemic StdDev Output)"]
        ORDINAL_HEAD["Ordinal Rank Head (Binned Class Probabilities)"]
    end

    PWEIGHTS --> DENSE1
    TOPOLOGY --> DENSE1
    DENSE1 --> NORM1
    NORM1 --> DENSE2
    DENSE2 --> NORM2

    NORM2 --> QUALITY_HEAD
    NORM2 --> VARIANCE_HEAD
    NORM2 --> ORDINAL_HEAD

    %% Styling
    classDef violet fill:#94167f,stroke:#f9ac53,stroke-width:2px,color:#ffffff;
    classDef amber fill:#f9ac53,stroke:#94167f,stroke-width:2px,color:#050d21,font-weight:bold;
    classDef blue fill:#153cb4,stroke:#f9ac53,stroke-width:2px,color:#ffffff;

    class PWEIGHTS,TOPOLOGY blue;
    class DENSE1,NORM1,DENSE2,NORM2 violet;
    class QUALITY_HEAD,VARIANCE_HEAD,ORDINAL_HEAD amber;
```
