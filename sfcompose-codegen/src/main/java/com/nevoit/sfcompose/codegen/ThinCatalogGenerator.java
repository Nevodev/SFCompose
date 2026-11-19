package com.nevoit.sfcompose.codegen;

import kotlin.Metadata;
import kotlin.metadata.*;
import kotlin.metadata.jvm.*;
import org.objectweb.asm.*;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.objectweb.asm.Opcodes.*;

public final class ThinCatalogGenerator {
    private static final String API = "com/nevoit/sfcompose/symbols/";
    private static final String[] WEIGHTS = {"Ultralight", "Thin", "Light", "Regular", "Medium", "Semibold", "Bold", "Heavy", "Black"};

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        try (JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
                symbol(jar, line.split("\t", -1));
            }
        }
    }

    private static ClassWriter begin(String name, String parent) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, parent, null);
        return writer;
    }

    private static KmClass metadata(String name, ClassKind kind, String parent) {
        KmClass km = new KmClass();
        km.setName(name.replace('$', '.'));
        Attributes.setKind(km, kind);
        Attributes.setVisibility(km, Visibility.PUBLIC);
        Attributes.setModality(km, Modality.FINAL);
        KmType type = new KmType();
        type.setClassifier(new KmClassifier.Class(parent));
        km.getSupertypes().add(type);
        return km;
    }

    private static void symbol(JarOutputStream jar, String[] fields) throws IOException {
        String name = API + fields[0];
        ClassWriter namespace = begin(name, "java/lang/Object");
        KmClass km = metadata(name, ClassKind.CLASS, "kotlin/Any");
        nesting(namespace, name);
        for (String child : fields[2].split(",")) {
            if (child.isEmpty()) continue;
            km.getNestedClasses().add(child);
            if (!name.contains("$")) namespace.visitInnerClass(name + "$" + child, name, child, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);
        }
        if (!fields[1].isEmpty()) {
            km.getNestedClasses().add("Companion");
            km.setCompanionObject("Companion");
            String companion = name + "$Companion";
            namespace.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "Companion", "L" + companion + ";", null, null).visitEnd();
            MethodVisitor init = namespace.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitTypeInsn(NEW, companion);
            init.visitInsn(DUP);
            init.visitMethodInsn(INVOKESPECIAL, companion, "<init>", "()V", false);
            init.visitFieldInsn(PUTSTATIC, name, "Companion", "L" + companion + ";");
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            companion(jar, companion, fields[1]);
        }
        // The Java root and Kotlin-only child tables avoid eager full-catalog PSI expansion.
        if (name.contains("$")) annotate(namespace, km);
        write(jar, name, namespace);
    }

    private static void companion(JarOutputStream jar, String name, String symbol) throws IOException {
        String parent = API + "PackagedStaticSymbol";
        ClassWriter writer = begin(name, parent);
        nesting(writer, name);
        KmClass km = metadata(name, ClassKind.COMPANION_OBJECT, parent);
        for (String weight : WEIGHTS) getter(writer, km, symbol, weight, false);
        getter(writer, km, symbol, "DynamicWeight", true);
        annotate(writer, km);
        MethodVisitor init = writer.visitMethod(0, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitLdcInsn(symbol);
        init.visitFieldInsn(GETSTATIC, API + "SymbolWeight", "Regular", "L" + API + "SymbolWeight;");
        init.visitMethodInsn(INVOKESPECIAL, parent, "<init>", "(Ljava/lang/String;L" + API + "SymbolWeight;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        write(jar, name, writer);
    }

    private static void getter(ClassWriter writer, KmClass km, String symbol, String label, boolean dynamic) {
        String result = API + (dynamic ? "PackagedDynamicSymbol" : "PackagedStaticSymbol");
        String methodName = "get" + label;
        String descriptor = "()L" + result + ";";
        KmProperty property = new KmProperty(label);
        Attributes.setVisibility(property, Visibility.PUBLIC);
        Attributes.setModality(property, Modality.FINAL);
        Attributes.setKind(property, MemberKind.DECLARATION);
        Attributes.setVisibility(property.getGetter(), Visibility.PUBLIC);
        Attributes.setModality(property.getGetter(), Modality.FINAL);
        Attributes.setNotDefault(property.getGetter(), true);
        KmType type = new KmType();
        type.setClassifier(new KmClassifier.Class(result));
        property.setReturnType(type);
        JvmExtensionsKt.setGetterSignature(property, new JvmMethodSignature(methodName, descriptor));
        km.getProperties().add(property);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, methodName, descriptor, null, null);
        method.visitCode();
        method.visitLdcInsn(symbol);
        if (!dynamic) method.visitFieldInsn(GETSTATIC, API + "SymbolWeight", label, "L" + API + "SymbolWeight;");
        method.visitMethodInsn(INVOKESTATIC, API + "SymbolEntries", dynamic ? "dynamic" : "fixed",
                "(Ljava/lang/String;" + (dynamic ? "" : "L" + API + "SymbolWeight;") + ")L" + result + ";", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void nesting(ClassWriter writer, String name) {
        int separator = name.lastIndexOf('$');
        if (separator < 0) return;
        String outer = name.substring(0, separator);
        nesting(writer, outer);
        writer.visitInnerClass(name, outer, name.substring(separator + 1), ACC_PUBLIC | ACC_STATIC | ACC_FINAL);
    }

    private static void annotate(ClassWriter writer, KmClass km) {
        Metadata data = new KotlinClassMetadata.Class(km, new JvmMetadataVersion(2, 2, 0), 0).write();
        AnnotationVisitor visitor = writer.visitAnnotation("Lkotlin/Metadata;", true);
        visitor.visit("k", data.k());
        visitor.visit("mv", data.mv());
        for (String key : new String[]{"d1", "d2"}) {
            AnnotationVisitor array = visitor.visitArray(key);
            for (String value : key.equals("d1") ? data.d1() : data.d2()) array.visit(null, value);
            array.visitEnd();
        }
        visitor.visit("xi", data.xi());
        visitor.visitEnd();
    }

    private static void write(JarOutputStream jar, String name, ClassWriter writer) throws IOException {
        writer.visitEnd();
        JarEntry entry = new JarEntry(name + ".class");
        entry.setTime(0);
        jar.putNextEntry(entry);
        jar.write(writer.toByteArray());
        jar.closeEntry();
    }
}
