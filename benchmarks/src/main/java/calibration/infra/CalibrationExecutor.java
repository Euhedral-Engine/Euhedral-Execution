package calibration.infra;

import io.euhedral_execution.benchmarks.frames.NoOpFrame;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.utils.MicroCalibrator;

public class CalibrationExecutor extends AbstractExecutor {

    private final WorkLimit workLimit;
    private final boolean random;
    private final MicroCalibrator calibrator = new MicroCalibrator();

    public CalibrationExecutor(int workUnitLimit, boolean random) {
        this(-1, new WorkLimit(workUnitLimit), random);
    }

    private CalibrationExecutor(int cpu, WorkLimit workLimit, boolean random) {
        super(cpu);
        this.workLimit = workLimit;
        this.random = random;
    }

    public void setWorkUnitLimit(int workUnitLimit) {
        if (workUnitLimit < 0) {
            throw new IllegalArgumentException("workUnitLimit must not be negative");
        }
        this.workLimit.value = workUnitLimit;
    }

    int workUnitLimit() {
        return this.workLimit.value;
    }

    @Override
    public void execute(AbstractFrame frame) {
        int currentWorkLimit = this.workLimit.value;
        if (this.random) {
            this.calibrator.cpuWork(
                    frame.getIdHash(), (int) Math.unsignedMultiplyHigh(frame.getRoutingHash(), currentWorkLimit));
        } else {
            this.calibrator.cpuWork(frame.getIdHash(), currentWorkLimit);
        }

        if (frame instanceof NoOpFrame nof) {
            nof.cpu = super.cpu;
        }
    }

    @Override
    public CalibrationExecutor hookOnClone(int cpu) {
        return new CalibrationExecutor(cpu, this.workLimit, this.random);
    }

    private static final class WorkLimit {
        volatile int value;

        WorkLimit(int value) {
            this.value = value;
        }
    }
}
