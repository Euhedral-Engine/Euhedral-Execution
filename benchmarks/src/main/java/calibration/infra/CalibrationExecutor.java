package calibration.infra;

import io.euhedral_execution.benchmarks.frames.NoOpFrame;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.utils.MicroCalibrator;

public class CalibrationExecutor extends AbstractExecutor {

    private final int workUnitLimit;
    private final boolean random;
    private final MicroCalibrator calibrator = new MicroCalibrator();

    public CalibrationExecutor(int workUnitLimit, boolean random) {
        this(-1, workUnitLimit, random);
    }

    private CalibrationExecutor(int cpu, int workUnitLimit, boolean random) {
        super(cpu);
        this.workUnitLimit = workUnitLimit;
        this.random = random;
    }

    @Override
    public void execute(AbstractFrame frame) {
        if (this.random) {
            this.calibrator.cpuWork(
                    frame.getIdHash(), (int) Math.unsignedMultiplyHigh(frame.getRoutingHash(), workUnitLimit));
        } else {
            this.calibrator.cpuWork(frame.getIdHash(), this.workUnitLimit);
        }

        if (frame instanceof NoOpFrame nof) {
            nof.cpu = super.cpu;
        }
    }

    @Override
    public CalibrationExecutor hookOnClone(int cpu) {
        return new CalibrationExecutor(cpu, this.workUnitLimit, this.random);
    }
}
