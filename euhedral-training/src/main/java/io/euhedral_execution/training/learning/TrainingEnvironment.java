package io.euhedral_execution.training.learning;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.util.cuda.CudaUtils;
import java.lang.management.MemoryUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports the DJL and PyTorch device environment used by scenario-conditioned learning.
 */
public final class TrainingEnvironment {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrainingEnvironment.class);
    private static final String ENGINE = "PyTorch";

    private TrainingEnvironment() {}

    public static void print() {
        Engine engine = Engine.getEngine(ENGINE);
        int gpuCount = engine.getGpuCount();
        LOGGER.info("DJL engine: {} {}", ENGINE, engine.getVersion());
        LOGGER.info("GPU count: {}", gpuCount);
        if (gpuCount == 0) {
            LOGGER.info("Default training device: CPU");
            return;
        }

        LOGGER.info("CUDA runtime: {}", CudaUtils.getCudaVersionString());
        for (int gpu = 0; gpu < gpuCount; gpu++) {
            MemoryUsage memory = CudaUtils.getGpuMemory(Device.gpu(gpu));
            LOGGER.info("GPU {} compute capability: {}", gpu, CudaUtils.getComputeCapability(gpu));
            LOGGER.info("GPU {} memory committed/max: {}/{}", gpu, memory.getCommitted(), memory.getMax());
        }
    }
}
