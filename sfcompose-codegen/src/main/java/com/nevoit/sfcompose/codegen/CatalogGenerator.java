package com.nevoit.sfcompose.codegen;

import kotlin.Metadata;
import kotlin.metadata.Attributes;
import kotlin.metadata.ClassKind;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmType;
import kotlin.metadata.Modality;
import kotlin.metadata.Visibility;
import kotlin.metadata.jvm.JvmMetadataVersion;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.objectweb.asm.Opcodes.*;

/** Emits Kotlin namespaces and independently removable payloads without compiling the full catalog. */
public final class CatalogGenerator {
    private static final String API = "com/nevoit/sfcompose/symbols/";
    private static final String DATA = "com/nevoit/sfcompose/catalog/data/";
    private static final String SOURCE = API + "SymbolDataSource";
    private final JarOutputStream jar;

    private CatalogGenerator(JarOutputStream jar) { this.jar = jar; }

    public static void main(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path dataOutput = Path.of(args[2]);
        Files.createDirectories(output.getParent());
        Path stamp = dataOutput.resolveSibling("symbol-data.sha256");
        MessageDigest fingerprint;
        try {
            fingerprint = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        // Bump this identifier when the generated payload or source-method bytecode changes.
        fingerprint.update("encoded-data-v1".getBytes(StandardCharsets.UTF_8));
        fingerprint.update(Files.readAllBytes(input.resolve("payloads.tsv")));
        fingerprint.update(Files.readAllBytes(input.resolve("sources.tsv")));
        byte[] expected = fingerprint.digest();
        if (!Files.exists(dataOutput) || !Files.exists(stamp) || !Arrays.equals(Files.readAllBytes(stamp), expected)) {
            try (JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(dataOutput), 1024 * 1024))) {
                jar.setLevel(1);
                CatalogGenerator generator = new CatalogGenerator(jar);
                String group = null;
                ClassWriter holder = null;
                for (String line : Files.readAllLines(input.resolve("payloads.tsv"), StandardCharsets.UTF_8)) {
                    String[] fields = line.split("\t", -1);
                    String next = payloadClass(fields[0]);
                    if (!next.equals(group)) {
                        if (holder != null) generator.write(group, holder);
                        group = next;
                        holder = begin(group, "java/lang/Object");
                    }
                    generator.payload(holder, fields[0], Files.readAllBytes(input.resolve("blobs").resolve(fields[1])));
                }
                if (holder != null) generator.write(group, holder);
                group = null;
                holder = null;
                for (String line : Files.readAllLines(input.resolve("sources.tsv"), StandardCharsets.UTF_8)) {
                    String[] fields = line.split("\t", -1);
                    String next = sourceClass(fields[0]);
                    if (!next.equals(group)) {
                        if (holder != null) generator.write(group, holder);
                        group = next;
                        holder = begin(group, "java/lang/Object");
                    }
                    generator.source(holder, fields);
                }
                if (holder != null) generator.write(group, holder);
            }
            Files.write(stamp, expected);
        }
        try (JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(output), 1024 * 1024))) {
            jar.setLevel(1);
            CatalogGenerator generator = new CatalogGenerator(jar);
            for (String line : Files.readAllLines(input.resolve("api.tsv"), StandardCharsets.UTF_8)) {
                generator.symbol(line.split("\t", -1));
            }
        }
    }

    private static ClassWriter begin(String name, String parent, String... interfaces) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, parent, interfaces);
        return writer;
    }

    private void write(String name, ClassWriter writer) throws IOException {
        writer.visitEnd();
        JarEntry entry = new JarEntry(name + ".class");
        entry.setTime(0);
        jar.putNextEntry(entry);
        jar.write(writer.toByteArray());
        jar.closeEntry();
    }

    private static void integer(MethodVisitor method, int value) {
        if (value <= 5) method.visitInsn(ICONST_0 + value);
        else if (value <= 127) method.visitIntInsn(BIPUSH, value);
        else if (value <= 32767) method.visitIntInsn(SIPUSH, value);
        else method.visitLdcInsn(value);
    }

    private static String payloadClass(String id) { return DATA + "P" + id.substring(0, 3); }

    private static String sourceClass(String id) { return DATA + id.substring(0, id.indexOf('_')); }

    private void payload(ClassWriter writer, String id, byte[] data) {
        try {
            byte[] expected = new byte[id.length() / 2];
            for (int index = 0; index < expected.length; index++) {
                expected[index] = (byte) Integer.parseInt(id.substring(index * 2, index * 2 + 2), 16);
            }
            if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(data))) {
                throw new IllegalStateException("Invalid catalog payload " + id);
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "open" + id, "()Ljava/io/InputStream;", null, null);
        method.visitCode();
        int count = (data.length + 11999) / 12000;
        integer(method, count);
        method.visitTypeInsn(ANEWARRAY, "java/lang/String");
        for (int index = 0; index < count; index++) {
            method.visitInsn(DUP);
            integer(method, index);
            byte[] chunk = java.util.Arrays.copyOfRange(data, index * 12000, Math.min(data.length, (index + 1) * 12000));
            method.visitLdcInsn(Base64.getEncoder().encodeToString(chunk));
            method.visitInsn(AASTORE);
        }
        method.visitMethodInsn(INVOKESTATIC, API + "EncodedSymbolData", "open",
                "([Ljava/lang/String;)Ljava/io/InputStream;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void source(ClassWriter writer, String[] fields) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, fields[0], "(Ljava/lang/String;)Ljava/io/InputStream;", null, null);
        method.visitCode();
        for (int index = 1; index < fields.length; index += 2) {
            var next = new org.objectweb.asm.Label();
            method.visitLdcInsn(fields[index]);
            method.visitVarInsn(ALOAD, 0);
            method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            method.visitJumpInsn(IFEQ, next);
            method.visitMethodInsn(INVOKESTATIC, payloadClass(fields[index + 1]), "open" + fields[index + 1],
                    "()Ljava/io/InputStream;", false);
            method.visitInsn(ARETURN);
            method.visitLabel(next);
            method.visitFrame(F_SAME, 0, null, 0, null);
        }
        method.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        method.visitInsn(DUP);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
        method.visitInsn(ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void symbol(String[] fields) throws IOException {
        String name = API + fields[0];
        String metadataName = API + fields[0].replace('$', '.');
        // Class namespaces let child references bypass the parent's Regular companion initializer.
        ClassWriter namespace = begin(name, "java/lang/Object");
        KmClass namespaceMetadata = new KmClass();
        namespaceMetadata.setName(metadataName);
        Attributes.setKind(namespaceMetadata, ClassKind.CLASS);
        Attributes.setVisibility(namespaceMetadata, Visibility.PUBLIC);
        Attributes.setModality(namespaceMetadata, Modality.FINAL);
        KmType namespaceParent = new KmType();
        namespaceParent.setClassifier(new KmClassifier.Class("kotlin/Any"));
        namespaceMetadata.getSupertypes().add(namespaceParent);
        nesting(namespace, name);
        for (String child : fields[5].split(",")) {
            if (child.isEmpty()) continue;
            namespaceMetadata.getNestedClasses().add(child);
            // Only the Java root needs child declarations in InnerClasses. Kotlin namespaces
            // use metadata; repeating their trees here makes Java PSI eagerly load the catalog.
            if (name.indexOf('$') < 0) {
                namespace.visitInnerClass(name + "$" + child, name, child, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);
            }
        }
        if (!fields[1].isEmpty()) {
            namespaceMetadata.getNestedClasses().add("Companion");
            namespaceMetadata.setCompanionObject("Companion");
            namespace.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "Companion", "L" + name + "$Companion;", null, null).visitEnd();
            MethodVisitor initializer = namespace.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            initializer.visitCode();
            initializer.visitTypeInsn(NEW, name + "$Companion");
            initializer.visitInsn(DUP);
            initializer.visitMethodInsn(INVOKESPECIAL, name + "$Companion", "<init>", "()V", false);
            initializer.visitFieldInsn(PUTSTATIC, name, "Companion", "L" + name + "$Companion;");
            initializer.visitInsn(RETURN);
            initializer.visitMaxs(0, 0);
            initializer.visitEnd();
        }
        // Kotlin eagerly deserializes all nested declarations of a Kotlin root.
        // A Java root keeps the complete catalog lazy while child companions retain value syntax.
        if (name.indexOf('$') >= 0) metadata(namespace, namespaceMetadata);
        write(name, namespace);
        if (fields[1].isEmpty()) return;
        name += "$Companion";
        String parent = fields[1].isEmpty() ? "java/lang/Object" : API + fields[1];
        ClassWriter writer = begin(name, parent);
        nesting(writer, name);
        KmClass metadata = new KmClass();
        metadata.setName(metadataName + ".Companion");
        Attributes.setKind(metadata, ClassKind.COMPANION_OBJECT);
        Attributes.setVisibility(metadata, Visibility.PUBLIC);
        Attributes.setModality(metadata, Modality.FINAL);
        KmType superclass = new KmType();
        superclass.setClassifier(new KmClassifier.Class(parent.equals("java/lang/Object") ? "kotlin/Any" : parent));
        metadata.getSupertypes().add(superclass);
        metadata(writer, metadata);
        MethodVisitor init = writer.visitMethod(0, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        if (!fields[1].isEmpty()) {
            init.visitLdcInsn(fields[2]);
            if (!fields[3].isEmpty()) {
                init.visitFieldInsn(GETSTATIC, API + "SymbolWeight", fields[3], "L" + API + "SymbolWeight;");
            }
            String signature = "(Ljava/lang/String;)Ljava/io/InputStream;";
            init.visitInvokeDynamicInsn("open", "()L" + SOURCE + ";",
                    new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                            "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                            "Ljava/lang/invoke/CallSite;", false),
                    Type.getMethodType(signature),
                    new Handle(H_INVOKESTATIC, sourceClass(fields[4]), fields[4], signature, false),
                    Type.getMethodType(signature));
        }
        String descriptor = fields[1].isEmpty() ? "()V" : "(Ljava/lang/String;" +
                (fields[3].isEmpty() ? "" : "L" + API + "SymbolWeight;") + "L" + SOURCE + ";)V";
        init.visitMethodInsn(INVOKESPECIAL, parent, "<init>", descriptor, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        write(name, writer);
    }

    private static void nesting(ClassWriter writer, String name) {
        int separator = name.lastIndexOf('$');
        if (separator < 0) return;
        String outer = name.substring(0, separator);
        nesting(writer, outer);
        writer.visitInnerClass(name, outer, name.substring(separator + 1), ACC_PUBLIC | ACC_STATIC | ACC_FINAL);
    }

    private static void metadata(ClassWriter writer, KmClass metadata) {
        Metadata annotation = new KotlinClassMetadata.Class(metadata, new JvmMetadataVersion(2, 2, 0), 0).write();
        AnnotationVisitor visitor = writer.visitAnnotation("Lkotlin/Metadata;", true);
        visitor.visit("k", annotation.k());
        visitor.visit("mv", annotation.mv());
        strings(visitor, "d1", annotation.d1());
        strings(visitor, "d2", annotation.d2());
        visitor.visit("xi", annotation.xi());
        visitor.visitEnd();
    }

    private static void strings(AnnotationVisitor annotation, String name, String[] values) {
        AnnotationVisitor array = annotation.visitArray(name);
        for (String value : values) array.visit(null, value);
        array.visitEnd();
    }
}
