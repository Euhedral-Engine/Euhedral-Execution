# Ubuntu GPU setup for the Euhedral trainer

The trainer uses DJL 0.36.0 with PyTorch 2.7.1. Maven packages the Java API, PyTorch engine, and
matching DJL JNI bridge. CUDA-enabled PyTorch itself is installed once in an Ubuntu Python virtual
environment and loaded through `PYTORCH_LIBRARY_PATH`.

This avoids both failure modes of the previous packaging approach:

- no Maven Shade uber jar
- no Windows, macOS, ARM, or duplicate CUDA libraries copied into the trainer distribution

## Requirements

- Ubuntu 22.04 or 24.04 x86_64
- the repository toolchain from `mise.toml` (currently Java 21, Maven 3.9.6, and Zig 0.16.0)
- Python 3.9 or later with `venv`
- NVIDIA GeForce RTX 5080
- NVIDIA Linux driver 570.26 or newer

PyTorch 2.7.1 provides an official CUDA 12.8 wheel. The wheel supplies the user-space CUDA and
PyTorch libraries, so the CUDA Toolkit is not needed unless `nvcc` or other CUDA development tools are
required. The NVIDIA driver is still required.

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

## Install CUDA-enabled PyTorch once

Create a dedicated environment so the trainer's native runtime remains isolated and reproducible:

```bash
sudo apt install python3-venv
python3 -m venv ~/.venvs/euhedral-pytorch
source ~/.venvs/euhedral-pytorch/bin/activate
python -m pip install --upgrade pip
python -m pip install torch==2.7.1 --index-url https://download.pytorch.org/whl/cu128
```

The trainer does not use torchvision or torchaudio, so they are intentionally omitted.

Verify the wheel and GPU:

```bash
python - <<'PY'
import torch
print("torch:", torch.__version__)
print("CUDA runtime:", torch.version.cuda)
print("CUDA available:", torch.cuda.is_available())
print("GPU:", torch.cuda.get_device_name(0))
print("capability:", torch.cuda.get_device_capability(0))
PY
```

Expected values include PyTorch `2.7.1`, CUDA `12.8`, and the RTX 5080.

## Build without an uber jar

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
    ├── project and Java dependencies
    ├── pytorch-engine-0.36.0.jar
    └── pytorch-jni-2.7.1-0.36.0.jar
```

No `pytorch-native-*` jar is packaged. The large CUDA libraries stay in the Python virtual
environment and are installed only once.

## Verify DJL on the RTX 5080

The packaged launcher discovers `torch/lib`, exports the DJL environment variables, selects GPU 0,
and starts the trainer:

```bash
source ~/.venvs/euhedral-pytorch/bin/activate
euhedral-training/target/trainer/bin/euhedral-training-gpu training-info
```

Expected output includes:

- PyTorch engine version
- one visible GPU
- CUDA runtime 12.8
- GPU compute capability
- visible GPU memory

The launcher sets:

```text
PYTORCH_LIBRARY_PATH=<venv>/lib/python*/site-packages/torch/lib
PYTORCH_VERSION=2.7.1
PYTORCH_FLAVOR=cu128
-Dai.djl.default_engine=PyTorch
-Dtraining.device=gpu0
```

Override its defaults when needed:

```bash
PYTHON_BIN=~/.venvs/euhedral-pytorch/bin/python \
TRAINING_DEVICE=gpu0 \
JAVA_OPTS="-Xms4g -Xmx16g" \
euhedral-training/target/trainer/bin/euhedral-training-gpu training-info
```

For a multi-GPU machine:

```bash
CUDA_VISIBLE_DEVICES=0 \
euhedral-training/target/trainer/bin/euhedral-training-gpu training-info
```

## Run training

All JVM and trainer system properties belong in `JAVA_OPTS`, because arguments after the jar are
application commands and arguments:

```bash
source ~/.venvs/euhedral-pytorch/bin/activate

JAVA_OPTS="\
  -Ddata=output/closed-loop/latest-training-data.txt \
  -Dmodel.output=output/model/best \
  -Dtraining.batchSize=8192" \
euhedral-training/target/trainer/bin/euhedral-training-gpu train-vector-finder
```

GPU-oriented defaults are:

- training batch: 4096
- candidate-screening batch: 65536
- FP32 parameters and activations

The model is small, so large batches are primarily useful for amortizing host-to-device transfer and
kernel-launch overhead. Measure before increasing them further.

## Troubleshooting

### `GPU count: 0`

Check both layers:

```bash
nvidia-smi
source ~/.venvs/euhedral-pytorch/bin/activate
python -c 'import torch; print(torch.cuda.is_available(), torch.version.cuda)'
```

Then run the packaged launcher rather than invoking the jar directly. The launcher supplies
`PYTORCH_LIBRARY_PATH`.

### Driver or CUDA initialization error

Upgrade the NVIDIA driver. CUDA 12.8 GA requires Linux driver 570.26 or newer.

### Launcher reports the wrong PyTorch version

Activate the intended virtual environment or set `PYTHON_BIN` explicitly:

```bash
PYTHON_BIN=~/.venvs/euhedral-pytorch/bin/python \
euhedral-training/target/trainer/bin/euhedral-training-gpu training-info
```

### Old model cannot be loaded

The ordinal DJL model is intentionally incompatible with the previous DL4J `.bin` regressor. Retrain
once from the existing merged benchmark corpus; later closed-loop iterations can continue from the
saved DJL model directory.
