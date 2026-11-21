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
                String[] fields = line.split("\t", -1);
                symbol(jar, fields);
                if (args.length > 2) source(Path.of(args[2]), fields);
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
        km.setName(name);
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
        boolean symbol = !fields[1].isEmpty();
        String parent = symbol ? API + "PackagedStaticSymbol" : "java/lang/Object";
        ClassWriter namespace = begin(name, parent);
        namespace.visitSource(fields[0] + ".kt", null);
        KmClass km = metadata(name, ClassKind.OBJECT, symbol ? parent : "kotlin/Any");
        namespace.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "INSTANCE", "L" + name + ";", null, null).visitEnd();
        MethodVisitor constructor = namespace.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        if (symbol) {
            constructor.visitLdcInsn(fields[1]);
            constructor.visitFieldInsn(GETSTATIC, API + "SymbolWeight", "Regular", "L" + API + "SymbolWeight;");
        }
        constructor.visitMethodInsn(INVOKESPECIAL, parent, "<init>", symbol ? "(Ljava/lang/String;L" + API + "SymbolWeight;)V" : "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor init = namespace.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(NEW, name);
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, name, "<init>", "()V", false);
        init.visitFieldInsn(PUTSTATIC, name, "INSTANCE", "L" + name + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String child : fields[2].split(",")) {
            if (child.isEmpty()) continue;
            String result = name + "$" + child;
            MethodVisitor method = property(namespace, km, child, result);
            method.visitFieldInsn(GETSTATIC, result, "INSTANCE", "L" + result + ";");
            method.visitInsn(ARETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        if (symbol) {
            for (String weight : WEIGHTS) getter(namespace, km, fields[1], weight, false);
            getter(namespace, km, fields[1], "DynamicWeight", true);
        }
        // Independent objects keep a single IDE file stub from recursively expanding the entire catalog.
        annotate(namespace, km);
        write(jar, name, namespace);
    }

    private static void getter(ClassWriter writer, KmClass km, String symbol, String label, boolean dynamic) {
        String result = API + (dynamic ? "PackagedDynamicSymbol" : "PackagedStaticSymbol");
        MethodVisitor method = property(writer, km, label, result);
        method.visitLdcInsn(symbol);
        if (!dynamic) method.visitFieldInsn(GETSTATIC, API + "SymbolWeight", label, "L" + API + "SymbolWeight;");
        method.visitMethodInsn(INVOKESTATIC, API + "SymbolEntries", dynamic ? "dynamic" : "fixed",
                "(Ljava/lang/String;" + (dynamic ? "" : "L" + API + "SymbolWeight;") + ")L" + result + ";", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static MethodVisitor property(ClassWriter writer, KmClass km, String label, String result) {
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
        return method;
    }

    private static void source(Path directory, String[] fields) throws IOException {
        Path file = directory.resolve(API + fields[0] + ".kt");
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder("@file:Suppress(\"INVISIBLE_REFERENCE\", \"INVISIBLE_MEMBER\")\n\npackage com.nevoit.sfcompose.symbols\n\nobject `")
                .append(fields[0]).append('`');
        if (!fields[1].isEmpty()) text.append(" : PackagedStaticSymbol(\"").append(fields[1]).append("\", SymbolWeight.Regular)");
        text.append(" {\n");
        for (String child : fields[2].split(",")) {
            if (!child.isEmpty()) text.append("    val ").append(child).append(": `").append(fields[0]).append('$').append(child)
                    .append("` get() = `").append(fields[0]).append('$').append(child).append("`\n");
        }
        if (!fields[1].isEmpty()) {
            for (String weight : WEIGHTS) text.append("    val ").append(weight)
                    .append(": PackagedStaticSymbol get() = SymbolEntries.fixed(\"").append(fields[1]).append("\", SymbolWeight.").append(weight).append(")\n");
            text.append("    val DynamicWeight: PackagedDynamicSymbol get() = SymbolEntries.dynamic(\"").append(fields[1]).append("\")\n");
        }
        Files.writeString(file, text.append("}\n"), StandardCharsets.UTF_8);
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
