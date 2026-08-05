package io.euhedral_execution.training.learning;

import static org.tensorflow.internal.c_api.global.tensorflow.TF_CloseSession;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_DeleteDeviceList;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_DeleteGraph;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_DeleteSession;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_DeleteSessionOptions;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_DeviceListCount;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_DeviceListName;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_DeviceListType;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_NewGraph;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_NewSession;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_NewSessionOptions;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_NewStatus;
import static org.tensorflow.internal.c_api.global.tensorflow.TF_SessionListDevices;

import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.TensorFlow;
import org.tensorflow.internal.c_api.TF_DeviceList;
import org.tensorflow.internal.c_api.TF_Graph;
import org.tensorflow.internal.c_api.TF_Session;
import org.tensorflow.internal.c_api.TF_SessionOptions;
import org.tensorflow.internal.c_api.TF_Status;

/**
 * Reports the TensorFlow Java runtime and host device-selection environment used by
 * scenario-conditioned learning.
 */
public final class TrainingEnvironment {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrainingEnvironment.class);

    public static void print() {
        LOGGER.info("TensorFlow runtime: {}", TensorFlow.version());
        LOGGER.info("CUDA_VISIBLE_DEVICES: {}", valueOrUnset("CUDA_VISIBLE_DEVICES"));
        LOGGER.info("LD_LIBRARY_PATH: {}", valueOrUnset("LD_LIBRARY_PATH"));
        List<String> devices = supportedDevices();
        LOGGER.info("Supported training.device values: {}", String.join(", ", devices));
    }

    private static List<String> supportedDevices() {
        TF_Status status = TF_NewStatus();
        TF_Graph graph = TF_NewGraph();
        TF_SessionOptions options = TF_NewSessionOptions();
        TF_Session session = null;
        TF_DeviceList deviceList = null;
        try {
            session = TF_NewSession(graph, options, status);
            status.throwExceptionIfNotOK();
            deviceList = TF_SessionListDevices(session, status);
            status.throwExceptionIfNotOK();

            LinkedHashSet<String> devices = new LinkedHashSet<>();
            devices.add("auto");
            devices.add("cpu");

            int gpuIndex = 0;
            int count = TF_DeviceListCount(deviceList);
            for (int index = 0; index < count; index++) {
                String type = TF_DeviceListType(deviceList, index, status).getString();
                status.throwExceptionIfNotOK();
                String name = TF_DeviceListName(deviceList, index, status).getString();
                status.throwExceptionIfNotOK();
                LOGGER.info("Visible TensorFlow device: {} ({})", name, type);
                if (!"GPU".equals(type)) {
                    continue;
                }
                devices.add("gpu" + gpuIndex);
                devices.add("cuda:" + gpuIndex);
                gpuIndex++;
            }
            return List.copyOf(devices);
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to enumerate TensorFlow devices; assuming CPU-only", error);
            return List.of("auto", "cpu");
        } finally {
            if (deviceList != null && !deviceList.isNull()) {
                TF_DeleteDeviceList(deviceList);
            }
            if (session != null && !session.isNull()) {
                try {
                    TF_CloseSession(session, status);
                } catch (RuntimeException ignored) {
                }
                try {
                    TF_DeleteSession(session, status);
                } catch (RuntimeException ignored) {
                }
            }
            if (options != null && !options.isNull()) {
                TF_DeleteSessionOptions(options);
            }
            if (graph != null && !graph.isNull()) {
                TF_DeleteGraph(graph);
            }
            if (status != null && !status.isNull()) {
                status.close();
            }
        }
    }

    private static String valueOrUnset(String key) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? "<unset>" : value;
    }

    private TrainingEnvironment() {}
}
