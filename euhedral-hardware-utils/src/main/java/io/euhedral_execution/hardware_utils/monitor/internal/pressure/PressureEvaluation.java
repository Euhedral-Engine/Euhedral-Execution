package io.euhedral_execution.hardware_utils.monitor.internal.pressure;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;

public record PressureEvaluation(PressureState state, HardwareUtilization candidate) {}
