package io.euhedral_execution.core.utils;

import io.euhedral_execution.core.generics.LatticeReceiver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class CommonVarHandles {

    public static VarHandle makeHandle(Class<?> targetClass, String fieldName, Class<?> fieldClass) {
        return makeHandle(MethodHandles.lookup(), targetClass, fieldName, fieldClass);
    }

    public static VarHandle makeHandle(MethodHandles.Lookup callerLookup, Class<?> targetClass, String fieldName, Class<?> fieldClass) {
        try {
            return MethodHandles.privateLookupIn(targetClass, callerLookup).findVarHandle(targetClass, fieldName, fieldClass);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static VarHandle closed(Class<?> clazz) {
        return closed(MethodHandles.lookup(), clazz);
    }

    public static VarHandle closed(MethodHandles.Lookup callerLookup, Class<?> clazz) {
        try {
            return MethodHandles.privateLookupIn(clazz, callerLookup)
                    .findVarHandle(clazz, "closed", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static VarHandle complete(Class<?> clazz) {
        return complete(MethodHandles.lookup(), clazz);
    }

    public static VarHandle complete(MethodHandles.Lookup callerLookup, Class<?> clazz) {
        try {
            return MethodHandles.privateLookupIn(clazz, callerLookup)
                    .findVarHandle(clazz, "complete", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static VarHandle downstream(Class<?> clazz) {
        return downstream(MethodHandles.lookup(), clazz);
    }

    public static VarHandle downstream(MethodHandles.Lookup callerLookup, Class<?> clazz) {
        try {
            return MethodHandles.privateLookupIn(clazz, callerLookup)
                    .findVarHandle(clazz, "downstream", LatticeReceiver.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CommonVarHandles() {

    }
}
