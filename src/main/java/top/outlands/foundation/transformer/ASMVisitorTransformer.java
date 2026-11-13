package top.outlands.foundation.transformer;

import top.outlands.foundation.IExplicitTransformer;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;

import static top.outlands.foundation.boot.Foundation.LOGGER;

/**
 * Raises the {@code api} field of the ASM visitors to ASM9 so that mods which were compiled against an
 * older ASM still accept Java 21+ class files. Every constructor that sets {@code api} is patched to
 * bump the value.
 */
public class ASMVisitorTransformer implements IExplicitTransformer {
    /** {@code Opcodes.ASM9}; classes newer than Java 21 need at least this api level. */
    private static final int ASM9_API = 589824;

    private static final String API_FIELD = "api";
    private static final String INIT = "<init>";

    private static final ClassFile CLASS_FILE = ClassFile.of();

    @Override
    public byte[] transform(byte[] basicClass) {
        try {
            final ClassModel model = CLASS_FILE.parse(basicClass);
            final String internalName = model.thisClass().asInternalName();
            final String delegateDescriptor = "(IL" + internalName + ";)V";
            if (!hasConstructor(model, "(I)V") && !hasConstructor(model, delegateDescriptor)) {
                LOGGER.warn("{} has no visitor constructor to patch", internalName.replace('/', '.'));
                return basicClass;
            }
            LOGGER.debug("Patching {}", internalName.replace('/', '.'));
            final ClassDesc owner = model.thisClass().asSymbol();
            return CLASS_FILE.transformClass(model, ClassTransform.transformingMethodBodies(
                    method -> method.methodName().equalsString(INIT)
                            && (method.methodType().stringValue().equals("(I)V")
                                || method.methodType().stringValue().equals(delegateDescriptor)),
                    (builder, element) -> {
                        if (element instanceof ReturnInstruction) {
                            appendApiBump(builder, owner);
                        }
                        builder.with(element);
                    }));
        } catch (Throwable t) {
            LOGGER.error(t);
        }
        return basicClass;
    }

    private static boolean hasConstructor(ClassModel model, String descriptor) {
        return model.methods().stream().anyMatch(method -> method.methodName().equalsString(INIT)
                && method.methodType().stringValue().equals(descriptor));
    }

    /**
     * Emits the equivalent of {@code if (api < 589824) { this.api = 589824; }}.
     */
    private static void appendApiBump(CodeBuilder builder, ClassDesc owner) {
        final Label alreadyNewEnough = builder.newLabel();
        builder.aload(0)
                .getfield(owner, API_FIELD, ConstantDescs.CD_int)
                .loadConstant(ASM9_API)
                .branch(Opcode.IF_ICMPGE, alreadyNewEnough)
                .aload(0)
                .loadConstant(ASM9_API)
                .putfield(owner, API_FIELD, ConstantDescs.CD_int)
                .labelBinding(alreadyNewEnough);
    }
}
