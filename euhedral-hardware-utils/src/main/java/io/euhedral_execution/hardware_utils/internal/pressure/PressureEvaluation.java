package io.euhedral_execution.hardware_utils.internal.pressure;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;

public record PressureEvaluation(PressureState newState, HardwareUtilization candidate) {

}
