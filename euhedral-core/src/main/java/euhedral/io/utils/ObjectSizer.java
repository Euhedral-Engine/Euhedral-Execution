package euhedral.io.utils;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

public class ObjectSizer {
    public static final int POINTER_SIZE = VM.current().classPointerSize();

    public static long sizeOf(Object obj) {
        if(obj == null) {
            return 0L;
        }

        return GraphLayout.parseInstance(obj).totalSize();
    }

    public static long sizeOf(Class<?> clazz) {
        if(clazz == null) {
            return 0L;
        }


        return ClassLayout.parseClass(clazz).instanceSize();
    }
}
