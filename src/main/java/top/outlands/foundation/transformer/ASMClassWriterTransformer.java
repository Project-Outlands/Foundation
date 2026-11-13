package top.outlands.foundation.transformer;

import net.minecraft.launchwrapper.Launch;
import top.outlands.foundation.IExplicitTransformer;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

import static top.outlands.foundation.boot.Foundation.LOGGER;

/**
 * Points ASM's {@code ClassWriter.getCommonSuperClass} at {@link #getCommonSuperClass(String, String)},
 * which resolves types through the launch class loaders instead of the class loader that happened to
 * define ASM.
 */
public class ASMClassWriterTransformer implements IExplicitTransformer {
    private static final String TARGET_METHOD = "getCommonSuperClass";
    private static final String TARGET_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

    private static final ClassDesc STRING = ClassDesc.of("java.lang.String");
    private static final ClassDesc SELF =
            ClassDesc.of("top.outlands.foundation.transformer.ASMClassWriterTransformer");
    private static final MethodTypeDesc TARGET_TYPE = MethodTypeDesc.of(STRING, STRING, STRING);

    private static final CodeTransform REPLACE_BODY = new CodeTransform() {
        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            // Drop the original body, only the delegate call below is kept.
        }

        @Override
        public void atEnd(CodeBuilder builder) {
            builder.aload(1)
                    .aload(2)
                    .invokestatic(SELF, TARGET_METHOD, TARGET_TYPE)
                    .areturn();
        }
    };

    private static final ClassFile CLASS_FILE = ClassFile.of();

    @Override
    public byte[] transform(byte[] basicClass) {
        try {
            final ClassModel model = CLASS_FILE.parse(basicClass);
            if (model.methods().stream().noneMatch(ASMClassWriterTransformer::isTarget)) {
                LOGGER.warn("{} has no {} to patch",
                        model.thisClass().asInternalName().replace('/', '.'), TARGET_METHOD);
                return basicClass;
            }
            LOGGER.debug("Patching {}", model.thisClass().asInternalName().replace('/', '.'));
            return CLASS_FILE.transformClass(model, ClassTransform.transformingMethods(
                    ASMClassWriterTransformer::isTarget,
                    MethodTransform.transformingCode(REPLACE_BODY)));
        }catch (Throwable t) {
            LOGGER.error(t);
        }
        return basicClass;
    }

    private static boolean isTarget(MethodModel method) {
        return method.methodName().equalsString(TARGET_METHOD)
                && method.methodType().stringValue().equals(TARGET_DESCRIPTOR);
    }

    public static String getCommonSuperClass(final String type1, final String type2) {
        if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) {
            return "java/lang/Object";
        }
        ClassLoader classLoader = Launch.appClassLoader;
        Class<?> class1;
        try {
            class1 = Class.forName(type1.replace('/', '.'), false, classLoader);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            try {
                class1 = Class.forName(type1.replace('/', '.'), false, Launch.classLoader);
            } catch (ClassNotFoundException | NoClassDefFoundError e1) {
                throw new TypeNotPresentException(type1, e1);
            }
        }
        Class<?> class2;
        try {
            class2 = Class.forName(type2.replace('/', '.'), false, classLoader);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            try {
                class2 = Class.forName(type2.replace('/', '.'), false, Launch.classLoader);
            } catch (ClassNotFoundException | NoClassDefFoundError e2) {
                throw new TypeNotPresentException(type2, e2);
            }
        }
        if (class1.isAssignableFrom(class2)) {
            return type1;
        }
        if (class2.isAssignableFrom(class1)) {
            return type2;
        }
        if (class1.isInterface() || class2.isInterface()) {
            return "java/lang/Object";
        } else {
            do {
                class1 = class1.getSuperclass();
            } while (!class1.isAssignableFrom(class2));
            return class1.getName().replace('.', '/');
        }
    }

}
