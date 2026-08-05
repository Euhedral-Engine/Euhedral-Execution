# Ubuntu GPU setup for the Euhedral trainer

The trainer uses TensorFlow Java 1.2.0 and ships its native GPU runtime through Maven. The thin
distribution keeps Java and project dependencies under `target/trainer/lib` and launches the
trainer without requiring a separate Python installation or PyTorch runtime.

## Requirements

- Ubuntu 22.04 or 24.04 x86_64
- the repository toolchain from `mise.toml` (currently Java 21, Maven 3.9.16, and Zig 0.16.0)
- NVIDIA Linux driver 570.26 or newer

The packaged TensorFlow GPU runtime supplies the user-space CUDA libraries it needs. The NVIDIA
driver is required.

## Install or update the NVIDIA driver

Ubuntu's supported automatic path is:

```bash
sudo apt update
sudo apt install ubuntu-drivers-common linux-headers-$(uname -r)
sudo ubuntu-drivers install
sudo reboot
```

After reboot:

```bash
nvidia-smi
cat /proc/driver/nvidia/version
```

The reported driver should be at least 570.26. A newer production driver is preferred.

## Build the trainer distribution

Build and install dependencies without compiling or running their tests, then run only the trainer
tests:

```bash
mise install
mise exec -- mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
mise exec -- mvn -B -pl euhedral-training test
```

Package the distribution:

```bash
mise exec -- mvn -B -pl euhedral-training -am package -Dmaven.test.skip=true
```

The output is a thin distribution:

```text
euhedral-training/target/trainer/
├── euhedral-training-0.0.7-SNAPSHOT.jar
├── bin/
│   └── euhedral-training-gpu
└── lib/
    └── project, Java, and TensorFlow runtime dependencies
```

## Verify TensorFlow runtime visibility

The packaged launcher starts the trainer directly. `training-info` uses the
`TrainingEnvironment` diagnostic:

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu training-info
```

Expected output includes:

- TensorFlow runtime version
- `CUDA_VISIBLE_DEVICES`
- `LD_LIBRARY_PATH`
- supported `training.device` values

Override JVM settings when needed:

```bash
JAVA_OPTS="-Xms4g -Xmx16g" \
euhedral-training/target/trainer/bin/euhedral-training-gpu training-info
```

For a multi-GPU machine:

```bash
CUDA_VISIBLE_DEVICES=0 \
euhedral-training/target/trainer/bin/euhedral-training-gpu training-info
```

## Run training

Application configuration belongs in the typed closed-loop configuration, not JVM properties. Set
`training.device=gpu0` or another supported value in that file, then run:

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu \
  closed-loop --config closed-loop.conf
```

GPU-oriented defaults are:

- training batch: 4096
- candidate-screening batch: 65536
- FP32 parameters and activations

The model is small, so large batches are primarily useful for amortizing host-to-device transfer
and kernel-launch overhead. Measure before increasing them further.

## Troubleshooting

### Training reports no visible GPU

Check the driver first:

```bash
nvidia-smi
```

Then run the packaged launcher rather than invoking the jar directly so the trainer sees the same
runtime layout that packaging produced.

### Driver or CUDA initialization error

Upgrade the NVIDIA driver. CUDA 12.8 GA requires Linux driver 570.26 or newer.

### Starting or resuming robust training

Start the robust closed loop from strict schema-v1 bootstrap vectors plus native exact-scenario
evidence, or resume its checkpointed workspace. Old merged corpora, model artifacts, and trainer
properties are not supported inputs.
