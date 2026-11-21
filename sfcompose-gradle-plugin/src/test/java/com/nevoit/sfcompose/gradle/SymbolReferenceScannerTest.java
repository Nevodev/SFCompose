package com.nevoit.sfcompose.gradle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.objectweb.asm.Opcodes.*;

/**
 * Exercises {@link SymbolReferenceScanner} with tiny synthetic class files. The scanner only needs
 * descriptors and symbolic owners (BasicInterpreter), so the referenced API classes are never defined.
 */
public class SymbolReferenceScannerTest {
    private static final String API = "com/nevoit/sfcompose/symbols/";
    private static final String SYMBOL_CLASS = API + "SFSymbols$Heart$Fill";
    private static final String COMPANION = SYMBOL_CLASS + "$Companion";
    private static final String PACKAGED = API + "PackagedStaticSymbol";
    private static final String ANIMATED = API + "PackagedAnimatedStaticSymbol";
    private static final String SINK = API + "SFSymbolEntryKt";
    private static final String SYMBOL = "heart.fill";
    private static final String HELPER = "com/nevoit/sfcompose/fixtures/ReferenceHelper";
    private static final String ROOT = API + "SFSymbols";
    private static final String HEART = ROOT + "$Heart";
    private static final String HEART_FILL = HEART + "$Fill";
    private static final String PERSON = ROOT + "$Person";
    private static final String PERSON_FILL = PERSON + "$Fill";
    private static final String SHAPES = ROOT + "$Shapes";
    private static final String CIRCLE = SHAPES + "$Circle";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void selectsExactBoldAnimatedReferenceWithoutRetainingParentRegular() throws Exception {
        Set<String> used = scan(consumer("com/nevoit/sfcompose/fixtures/BoldAnimated", method -> {
            companion(method);
            weight(method, "Bold");
            animated(method);
            sinkAnimated(method);
        }));

        assertEquals(Set.of(SYMBOL + ":Bold:Animated"), used);
    }

    @Test
    public void selectsBareRegularCompanionReference() throws Exception {
        Set<String> used = scan(consumer("com/nevoit/sfcompose/fixtures/BareRegular", method -> {
            companion(method);
            sinkRegular(method);
        }));

        assertEquals(Set.of(SYMBOL + ":Regular"), used);
    }

    @Test
    public void mergesSelectionsFromBothBranches() throws Exception {
        Set<String> used = scan(consumer("com/nevoit/sfcompose/fixtures/Branches", method -> {
            Label regular = new Label();
            Label join = new Label();
            method.visitInsn(ICONST_1);
            method.visitJumpInsn(IFEQ, regular);
            companion(method);
            weight(method, "Bold");
            method.visitJumpInsn(GOTO, join);
            method.visitLabel(regular);
            companion(method);
            weight(method, "Regular");
            method.visitLabel(join);
            sinkRegular(method);
        }));

        assertEquals(Set.of(SYMBOL + ":Bold", SYMBOL + ":Regular"), used);
    }

    @Test
    public void tracksLocalVariableAliases() throws Exception {
        Set<String> used = scan(consumer("com/nevoit/sfcompose/fixtures/LocalAlias", method -> {
            companion(method);
            weight(method, "Bold");
            method.visitVarInsn(ASTORE, 0);
            method.visitVarInsn(ALOAD, 0);
            animated(method);
            sinkAnimated(method);
        }));

        assertEquals(Set.of(SYMBOL + ":Bold:Animated"), used);
    }

    @Test
    public void followsFieldReturningHelperInSecondClass() throws Exception {
        Set<String> used = scan(consumer("com/nevoit/sfcompose/fixtures/HelperConsumer", method -> {
            method.visitMethodInsn(INVOKESTATIC, HELPER, "install", "()V", false);
            method.visitMethodInsn(INVOKESTATIC, HELPER, "provide", "()L" + PACKAGED + ";", false);
            animated(method);
            sinkAnimated(method);
        }), helper());

        assertEquals(Set.of(SYMBOL + ":Bold:Animated"), used);
    }

    @Test
    public void tracksArrayContainerRoundTrip() throws Exception {
        Set<String> used = scan(consumer("com/nevoit/sfcompose/fixtures/ArrayRoundTrip", method -> {
            method.visitInsn(ICONST_1);
            method.visitTypeInsn(ANEWARRAY, PACKAGED);
            method.visitInsn(DUP);
            method.visitInsn(ICONST_0);
            companion(method);
            weight(method, "Bold");
            method.visitInsn(AASTORE);
            method.visitInsn(ICONST_0);
            method.visitInsn(AALOAD);
            animated(method);
            sinkAnimated(method);
        }));

        assertEquals(Set.of(SYMBOL + ":Bold:Animated"), used);
    }

    @Test
    public void tracksMutableContainerRoundTrip() throws Exception {
        Set<String> used = scan(consumer("com/nevoit/sfcompose/fixtures/ContainerRoundTrip", method -> {
            method.visitTypeInsn(NEW, "java/util/ArrayList");
            method.visitInsn(DUP);
            method.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            method.visitVarInsn(ASTORE, 0);
            method.visitVarInsn(ALOAD, 0);
            companion(method);
            weight(method, "Bold");
            method.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
            method.visitInsn(POP);
            method.visitVarInsn(ALOAD, 0);
            method.visitInsn(ICONST_0);
            method.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;", false);
            method.visitTypeInsn(CHECKCAST, PACKAGED);
            animated(method);
            sinkAnimated(method);
        }));

        assertEquals(Set.of(SYMBOL + ":Bold:Animated"), used);
    }

    @Test
    public void tracksAnimationAppliedInsideAHelperParameter() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, HELPER, null, "java/lang/Object", null);
        MethodVisitor helper = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "animate",
                "(L" + PACKAGED + ";)L" + ANIMATED + ";", null, null);
        helper.visitCode();
        helper.visitVarInsn(ALOAD, 0);
        animated(helper);
        helper.visitInsn(ARETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        writer.visitEnd();
        Set<String> used = scan(consumer("example/CallHelper", method -> {
            companion(method);
            weight(method, "Bold");
            method.visitMethodInsn(INVOKESTATIC, HELPER, "animate", "(L" + PACKAGED + ";)L" + ANIMATED + ";", false);
            sinkAnimated(method);
        }), writer.toByteArray());
        assertEquals(Set.of(SYMBOL + ":Bold:Animated"), used);
    }

    @Test
    public void tracksInterfaceProviderImplementation() throws Exception {
        String provider = "example/IconProvider";
        String implementation = "example/ActualProvider";
        ClassWriter contract = new ClassWriter(0);
        contract.visit(V1_8, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, provider, null, "java/lang/Object", null);
        contract.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "icon", "()L" + PACKAGED + ";", null, null).visitEnd();
        contract.visitEnd();
        ClassWriter actual = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        actual.visit(V1_8, ACC_PUBLIC, implementation, null, "java/lang/Object", new String[]{provider});
        MethodVisitor icon = actual.visitMethod(ACC_PUBLIC, "icon", "()L" + PACKAGED + ";", null, null);
        icon.visitCode();
        companion(icon);
        weight(icon, "Bold");
        icon.visitInsn(ARETURN);
        icon.visitMaxs(0, 0);
        icon.visitEnd();
        actual.visitEnd();
        Set<String> used = scan(consumer("example/UseProvider", method -> {
            method.visitInsn(ACONST_NULL);
            method.visitMethodInsn(INVOKEINTERFACE, provider, "icon", "()L" + PACKAGED + ";", true);
            animated(method);
            sinkAnimated(method);
        }), contract.toByteArray(), actual.toByteArray());
        assertEquals(Set.of(SYMBOL + ":Bold:Animated"), used);
    }

    @Test
    public void selectsBoldThroughTopLevelObjectChainWithoutParentRegular() throws Exception {
        Set<String> used = scan(Map.of(HEART_FILL, "heart.fill", PERSON_FILL, "person.fill"),
                consumer("com/nevoit/sfcompose/fixtures/ObjectChain", method -> {
                    root(method, "getHeart", HEART);
                    child(method, HEART, HEART_FILL);
                    bold(method, HEART_FILL);
                    sinkRegular(method);

                    root(method, "getPerson", PERSON);
                    child(method, PERSON, PERSON_FILL);
                    bold(method, PERSON_FILL);
                    sinkRegular(method);
                }));

        assertEquals(Set.of("heart.fill:Bold", "person.fill:Bold"), used);
    }

    @Test
    public void followsGroupingObjectAndPlainLeafEntry() throws Exception {
        Set<String> used = scan(Map.of(HEART_FILL, "heart.fill", CIRCLE, "shape.circle"),
                consumer("com/nevoit/sfcompose/fixtures/GroupingAndPlain", method -> {
                    root(method, "getShapes", SHAPES);
                    method.visitMethodInsn(INVOKEVIRTUAL, SHAPES, "getCircle", "()L" + CIRCLE + ";", false);
                    bold(method, CIRCLE);
                    sinkRegular(method);

                    method.visitFieldInsn(GETSTATIC, HEART_FILL, "INSTANCE", "L" + HEART_FILL + ";");
                    bold(method, HEART_FILL);
                    sinkRegular(method);
                }));

        assertEquals(Set.of("shape.circle:Bold", "heart.fill:Bold"), used);
    }

    private Set<String> scan(byte[]... classes) throws Exception {
        return scan(Map.of(SYMBOL_CLASS, SYMBOL), classes);
    }

    private Set<String> scan(Map<String, String> namespaces, byte[]... classes) throws Exception {
        File directory = temporary.newFolder();
        for (byte[] bytes : classes) {
            String name = new ClassReader(bytes).getClassName();
            Path file = directory.toPath().resolve(name + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
        return new SymbolReferenceScanner(namespaces).scan(List.of(), List.of(directory));
    }

    private static byte[] consumer(String name, Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        body.accept(method);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] helper() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "Cached", "L" + PACKAGED + ";", null, null).visitEnd();

        MethodVisitor install = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "install", "()V", null, null);
        install.visitCode();
        companion(install);
        weight(install, "Bold");
        install.visitFieldInsn(PUTSTATIC, HELPER, "Cached", "L" + PACKAGED + ";");
        install.visitInsn(RETURN);
        install.visitMaxs(0, 0);
        install.visitEnd();

        MethodVisitor provide = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "provide", "()L" + PACKAGED + ";", null, null);
        provide.visitCode();
        provide.visitFieldInsn(GETSTATIC, HELPER, "Cached", "L" + PACKAGED + ";");
        provide.visitInsn(ARETURN);
        provide.visitMaxs(0, 0);
        provide.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void companion(MethodVisitor method) {
        method.visitFieldInsn(GETSTATIC, SYMBOL_CLASS, "Companion", "L" + COMPANION + ";");
    }

    private static void root(MethodVisitor method, String getter, String namespace) {
        method.visitFieldInsn(GETSTATIC, ROOT, "INSTANCE", "L" + ROOT + ";");
        method.visitMethodInsn(INVOKEVIRTUAL, ROOT, getter, "()L" + namespace + ";", false);
    }

    private static void child(MethodVisitor method, String owner, String child) {
        method.visitMethodInsn(INVOKEVIRTUAL, owner, "getFill", "()L" + child + ";", false);
    }

    private static void bold(MethodVisitor method, String owner) {
        method.visitMethodInsn(INVOKEVIRTUAL, owner, "getBold", "()L" + PACKAGED + ";", false);
    }

    private static void weight(MethodVisitor method, String name) {
        method.visitMethodInsn(INVOKEVIRTUAL, COMPANION, "get" + name, "()L" + PACKAGED + ";", false);
    }

    private static void animated(MethodVisitor method) {
        method.visitMethodInsn(INVOKEVIRTUAL, PACKAGED, "getAnimated", "()L" + ANIMATED + ";", false);
    }

    private static void sinkAnimated(MethodVisitor method) {
        method.visitMethodInsn(INVOKESTATIC, SINK, "SFSymbol", "(L" + API + "AnimatedStaticSymbol;)V", false);
    }

    private static void sinkRegular(MethodVisitor method) {
        method.visitMethodInsn(INVOKESTATIC, SINK, "SFSymbol", "(L" + API + "StaticSymbol;)V", false);
    }
}
