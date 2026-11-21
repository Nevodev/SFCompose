package com.nevoit.sfcompose.codegen;

import kotlin.Metadata;
import kotlin.metadata.Attributes;
import kotlin.metadata.ClassKind;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmProperty;
import kotlin.metadata.jvm.JvmExtensionsKt;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Reads metadata without loading symbol objects, whose renderer parent is not on this test classpath. */
public class ThinCatalogGeneratorTest {
    private static final String API = "com.nevoit.sfcompose.symbols.";
    private static final String API_PATH = "com/nevoit/sfcompose/symbols/";
    private static final List<String> GETTERS = List.of(
            "Ultralight", "Thin", "Light", "Regular", "Medium", "Semibold", "Bold", "Heavy", "Black", "DynamicWeight");

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void publicRootIsAnIndependentKotlinObject() throws Exception {
        assertSymbolTree("SFSymbols");
    }

    @Test
    public void privateRootIsAnIndependentKotlinObject() throws Exception {
        assertSymbolTree("PrivateSFSymbols");
    }

    @Test
    public void deepNamespacesAreIndependentKotlinObjects() throws Exception {
        Path jar = generate("SFSymbols", Arrays.asList(
                "SFSymbols\t\tDeep",
                "SFSymbols$Deep\t\tLevel",
                "SFSymbols$Deep$Level\t\tLeaf",
                "SFSymbols$Deep$Level$Leaf\tDeep.Level.Leaf\t"));

        Set<String> entries = jarEntries(jar);
        for (String name : new String[]{
                "SFSymbols",
                "SFSymbols$Deep",
                "SFSymbols$Deep$Level",
                "SFSymbols$Deep$Level$Leaf"}) {
            assertTrue("missing generated class " + name, entries.contains(API_PATH + name + ".class"));
        }
        assertNoCompanions(entries);

        assertNamespace(jar, "SFSymbols", Map.of("Deep", API + "SFSymbols$Deep"));
        assertNamespace(jar, "SFSymbols$Deep", Map.of("Level", API + "SFSymbols$Deep$Level"));
        assertNamespace(jar, "SFSymbols$Deep$Level", Map.of("Leaf", API + "SFSymbols$Deep$Level$Leaf"));
        assertSymbolObject(jar, "SFSymbols$Deep$Level$Leaf", null, null);
    }

    @Test
    public void writesOneKotlinSourceFilePerTopLevelObject() throws Exception {
        Path sources = temporary.newFolder("sources").toPath();
        generate("SFSymbols", symbolRows("SFSymbols"), sources);

        Path packageDirectory = sources.resolve(API_PATH);
        assertEquals(set("SFSymbols.kt", "SFSymbols$Heart.kt", "SFSymbols$Heart$Fill.kt", "SFSymbols$Wifi.kt"),
                kotlinFiles(packageDirectory));

        String root = Files.readString(packageDirectory.resolve("SFSymbols.kt"), StandardCharsets.UTF_8);
        assertTrue(root.contains("object `SFSymbols` {"));
        assertTrue(root.contains("val Heart: `SFSymbols$Heart` get() = `SFSymbols$Heart`"));
        assertTrue(root.contains("val Wifi: `SFSymbols$Wifi` get() = `SFSymbols$Wifi`"));
        assertFalse("the root source must not inline the rest of the catalog",
                root.contains("SFSymbols$Heart$Fill"));

        String heart = Files.readString(packageDirectory.resolve("SFSymbols$Heart.kt"), StandardCharsets.UTF_8);
        assertTrue(heart.contains("object `SFSymbols$Heart` : PackagedStaticSymbol(\"Heart\", SymbolWeight.Regular)"));
        assertTrue(heart.contains("val Fill: `SFSymbols$Heart$Fill` get() = `SFSymbols$Heart$Fill`"));
        assertTrue(heart.contains("val Bold: PackagedStaticSymbol get() = SymbolEntries.fixed(\"Heart\", SymbolWeight.Bold)"));
        assertTrue(heart.contains("val DynamicWeight: PackagedDynamicSymbol get() = SymbolEntries.dynamic(\"Heart\")"));

        String fill = Files.readString(packageDirectory.resolve("SFSymbols$Heart$Fill.kt"), StandardCharsets.UTF_8);
        assertTrue(fill.contains("object `SFSymbols$Heart$Fill` : PackagedStaticSymbol(\"Heart.Fill\", SymbolWeight.Regular)"));
    }

    private void assertSymbolTree(String root) throws Exception {
        Path jar = generate(root, symbolRows(root));

        Set<String> entries = jarEntries(jar);
        for (String name : new String[]{root, root + "$Heart", root + "$Heart$Fill", root + "$Wifi"}) {
            assertTrue("missing generated class " + name, entries.contains(API_PATH + name + ".class"));
        }
        assertNoCompanions(entries);

        assertNamespace(jar, root, Map.of(
                "Heart", API + root + "$Heart",
                "Wifi", API + root + "$Wifi"));
        assertSymbolObject(jar, root + "$Heart", "Fill", API + root + "$Heart$Fill");
        assertSymbolObject(jar, root + "$Heart$Fill", null, null);
        assertSymbolObject(jar, root + "$Wifi", null, null);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> rootType = Class.forName(API + root, false, loader);
            Field instance = rootType.getDeclaredField("INSTANCE");
            assertTrue("object INSTANCE must be static", Modifier.isStatic(instance.getModifiers()));
            assertTrue("object INSTANCE must be final", Modifier.isFinal(instance.getModifiers()));
            assertSame("the root singleton must be typed as the root object", rootType, instance.getType());
        }
    }

    private static List<String> symbolRows(String root) {
        return Arrays.asList(
                root + "\t\tHeart,Wifi",
                root + "$Heart\tHeart\tFill",
                root + "$Heart$Fill\tHeart.Fill\t",
                root + "$Wifi\tWifi\t");
    }

    private static void assertNamespace(Path jar, String binaryName, Map<String, String> children) throws Exception {
        KmClass km = readMetadata(jar, binaryName);
        assertEquals(API_PATH + binaryName, km.getName());
        assertEquals("namespace " + binaryName + " must be a top-level object", ClassKind.OBJECT,
                Attributes.getKind(km));
        assertEquals("top-level objects must not nest classes", List.of(), km.getNestedClasses());
        assertNull("top-level objects must not declare a companion", km.getCompanionObject());
        assertEquals("properties must expose exactly the direct children", children, propertyTypes(km, jar, binaryName));
    }

    private static void assertSymbolObject(Path jar, String binaryName, String childName, String childType)
            throws Exception {
        KmClass km = readMetadata(jar, binaryName);
        assertEquals(API_PATH + binaryName, km.getName());
        assertEquals("symbol " + binaryName + " must be a top-level object", ClassKind.OBJECT,
                Attributes.getKind(km));
        assertEquals("top-level objects must not nest classes", List.of(), km.getNestedClasses());
        assertNull("symbols extend PackagedStaticSymbol and declare no companion", km.getCompanionObject());

        Map<String, String> types = propertyTypes(km, jar, binaryName);
        if (childName != null) assertEquals(childType, types.get(childName));
        for (String getter : GETTERS) {
            String expected = API + ("DynamicWeight".equals(getter) ? "PackagedDynamicSymbol" : "PackagedStaticSymbol");
            assertEquals("getter " + getter + " on " + binaryName, expected, types.get(getter));
        }
        assertEquals("unexpected properties on " + binaryName,
                (childName == null ? 0 : 1) + GETTERS.size(), types.size());
    }

    private static Map<String, String> propertyTypes(KmClass km, Path jar, String binaryName) throws Exception {
        Set<String> methods = methodSignatures(jar, binaryName);
        Map<String, String> types = new LinkedHashMap<>();
        for (KmProperty property : km.getProperties()) {
            JvmMethodSignature signature = JvmExtensionsKt.getGetterSignature(property);
            assertNotNull("property " + property.getName() + " on " + binaryName + " needs a getter signature",
                    signature);
            assertTrue("property " + property.getName() + " has no matching method " + signature.getName()
                            + signature.getDescriptor() + " on " + binaryName,
                    methods.contains(signature.getName() + signature.getDescriptor()));

            KmClassifier classifier = property.getReturnType().getClassifier();
            assertTrue("property " + property.getName() + " must return a class",
                    classifier instanceof KmClassifier.Class);
            types.put(property.getName(), ((KmClassifier.Class) classifier).getName().replace('/', '.'));
        }
        return types;
    }

    private Path generate(String root, List<String> rows) throws Exception {
        return generate(root, rows, null);
    }

    private Path generate(String root, List<String> rows, Path sources) throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path namespaces = directory.resolve("namespaces.tsv");
        Files.write(namespaces, rows, StandardCharsets.UTF_8);
        Path jar = directory.resolve(root + ".jar");
        String[] args = sources == null
                ? new String[]{namespaces.toString(), jar.toString()}
                : new String[]{namespaces.toString(), jar.toString(), sources.toString()};
        ThinCatalogGenerator.main(args);
        assertTrue("generator did not produce " + jar, Files.isRegularFile(jar));
        return jar;
    }

    private static KmClass readMetadata(Path jar, String binaryName) throws Exception {
        Metadata annotation = metadataAnnotation(jarEntry(jar, API_PATH + binaryName + ".class"));
        KotlinClassMetadata metadata = KotlinClassMetadata.readStrict(annotation);
        assertNotNull("unreadable Kotlin metadata on " + binaryName, metadata);
        assertTrue("expected a Kotlin class on " + binaryName, metadata instanceof KotlinClassMetadata.Class);
        return ((KotlinClassMetadata.Class) metadata).getKmClass();
    }

    private static Metadata metadataAnnotation(byte[] classBytes) {
        Map<String, Object> values = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!"Lkotlin/Metadata;".equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        values.put(name, value);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        List<Object> items = new ArrayList<>();
                        values.put(name, items);
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                items.add(value);
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return (Metadata) Proxy.newProxyInstance(Metadata.class.getClassLoader(),
                new Class<?>[]{Metadata.class},
                (proxy, method, arguments) -> metadataValue(proxy, method, arguments, values));
    }

    private static Object metadataValue(Object proxy, java.lang.reflect.Method method, Object[] arguments,
                                        Map<String, Object> values) {
        switch (method.getName()) {
            case "annotationType":
                return Metadata.class;
            case "toString":
                return "kotlin.Metadata";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == arguments[0];
            default:
                break;
        }

        Object value = values.get(method.getName());
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            if (method.getReturnType() == int[].class) {
                int[] result = new int[list.size()];
                for (int i = 0; i < result.length; i++) result[i] = ((Number) list.get(i)).intValue();
                return result;
            }
            return list.stream().map(String::valueOf).toArray(String[]::new);
        }
        if (value != null) return value;

        Class<?> type = method.getReturnType();
        if (type == int.class) return 0;
        if (type == int[].class) return new int[0];
        if (type == String[].class) return new String[0];
        if (type == String.class) return "";
        return null;
    }

    private static Set<String> methodSignatures(Path jar, String binaryName) throws Exception {
        byte[] classBytes = jarEntry(jar, API_PATH + binaryName + ".class");
        Set<String> methods = new LinkedHashSet<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                methods.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static byte[] jarEntry(Path jar, String name) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(name);
            assertNotNull("missing jar entry " + name, entry);
            try (InputStream in = file.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static Set<String> jarEntries(Path jar) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
        }
        return names;
    }

    private static Set<String> kotlinFiles(Path directory) throws Exception {
        Set<String> files = new LinkedHashSet<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .forEach(path -> files.add(path.getFileName().toString()));
        }
        return files;
    }

    private static void assertNoCompanions(Set<String> entries) {
        for (String entry : entries) {
            assertFalse("independent objects must not emit companion classes: " + entry,
                    entry.endsWith("$Companion.class"));
        }
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
