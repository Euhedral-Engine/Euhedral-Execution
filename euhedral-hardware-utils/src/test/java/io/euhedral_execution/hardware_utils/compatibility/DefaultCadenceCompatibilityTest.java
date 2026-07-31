package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class DefaultCadenceCompatibilityTest {

    @Test
    void defaultsToExactlyTwoHundredMilliseconds() throws Exception {
        AtomicBoolean durationCreated = new AtomicBoolean();
        AtomicBoolean delegates = new AtomicBoolean();
        byte[] bytes = Files.readAllBytes(TestPaths.classesDirectory().resolve(
                "io/euhedral_execution/hardware_utils/ResourceMonitor.class"));
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals("<init>") || !descriptor.equals(
                        "(Lio/euhedral_execution/hardware_utils/TopologyMapper;)V")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private boolean sawTwoHundred;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (Long.valueOf(200L).equals(value)) {
                            this.sawTwoHundred = true;
                        }
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        if ((opcode == Opcodes.SIPUSH || opcode == Opcodes.BIPUSH)
                                && operand == 200) {
                            this.sawTwoHundred = true;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String invokedName,
                            String invokedDescriptor, boolean isInterface) {
                        if (this.sawTwoHundred
                                && owner.equals("java/time/Duration")
                                && invokedName.equals("ofMillis")
                                && invokedDescriptor.equals("(J)Ljava/time/Duration;")) {
                            durationCreated.set(true);
                        }
                        if (durationCreated.get()
                                && opcode == Opcodes.INVOKESPECIAL
                                && owner.equals(
                                "io/euhedral_execution/hardware_utils/ResourceMonitor")
                                && invokedName.equals("<init>")
                                && invokedDescriptor.equals(
                                "(Lio/euhedral_execution/hardware_utils/TopologyMapper;Ljava/time/Duration;)V")) {
                            delegates.set(true);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(durationCreated.get(),
                "default constructor does not call Duration.ofMillis(200)");
        assertTrue(delegates.get(), "default constructor does not delegate with the duration");
        assertEquals(200_000_000L, Duration.ofMillis(200).toNanos());
    }
}
