package euhedral.hardware_utils.test_utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

public class ContainerMain {
    public static void main(String[] args) throws Throwable {
        Class<?> clazz = Class.forName(args[0]);

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType mt = MethodType.methodType(void.class, String[].class);
        MethodHandle main = lookup.findStatic(clazz, "main", mt);

        main.invoke((Object) Arrays.copyOfRange(args, 1, args.length));
    }
}
