package com.nevoit.sfcompose.gradle;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Tracks symbol values through locals, branches, fields, helpers and common containers. */
public final class SymbolReferenceScanner {
    private static final String API = "com/nevoit/sfcompose/symbols/";
    private static final Set<String> WEIGHTS = Set.of("Ultralight", "Thin", "Light", "Regular", "Medium", "Semibold", "Bold", "Heavy", "Black", "DynamicWeight");
    private final Map<String, String> namespaces;
    private final Map<String, Set<String>> fields = new HashMap<>(), returns = new HashMap<>();
    private final Map<String, Set<String>> parameters = new HashMap<>();
    private final Set<String> knownMethods = new HashSet<>();
    private final Set<String> used = new TreeSet<>();
    private final List<ClassNode> classes = new ArrayList<>();
    private final Map<String, ClassNode> hierarchy = new HashMap<>();

    public SymbolReferenceScanner(Map<String, String> namespaces) { this.namespaces = namespaces; }

    public Set<String> scan(Collection<File> jars, Collection<File> directories) throws IOException, AnalyzerException {
        for (File directory : directories) {
            if (!directory.isDirectory()) continue;
            try (var paths = Files.walk(directory.toPath())) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) read(Files.readAllBytes(path));
            }
        }
        for (File file : jars) {
            try (JarFile jar = new JarFile(file)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) read(jar.getInputStream(entry).readAllBytes());
                }
            }
        }
        int previous;
        do {
            previous = stateSize();
            for (ClassNode type : classes) for (MethodNode method : type.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                new Analyzer<BasicValue>(new Tracking(type.name + "#" + method.name + method.desc)).analyze(type.name, method);
            }
        } while (previous != stateSize());
        return used;
    }

    private int stateSize() {
        return used.size() + fields.values().stream().mapToInt(Set::size).sum() + returns.values().stream().mapToInt(Set::size).sum()
                + parameters.values().stream().mapToInt(Set::size).sum();
    }

    private void read(byte[] bytes) {
        // Full renderer/framework classes cannot introduce a generated catalog reference.
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!text.contains(API)) return;
        ClassReader reader = new ClassReader(bytes);
        if (reader.getClassName().startsWith(API) || reader.getClassName().startsWith("androidx/")) return;
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_DEBUG);
        classes.add(node);
        hierarchy.put(node.name, node);
        node.methods.forEach(method -> knownMethods.add(node.name + "#" + method.name + method.desc));
    }

    private boolean subtype(String name, String parent, Set<String> visited) {
        if (name.equals(parent)) return true;
        ClassNode node = hierarchy.get(name);
        if (node == null || !visited.add(name)) return false;
        if (node.superName != null && subtype(node.superName, parent, visited)) return true;
        return node.interfaces.stream().anyMatch(type -> subtype(type, parent, visited));
    }

    private Set<String> targets(MethodInsnNode call) {
        Set<String> result = new HashSet<>();
        String exact = call.owner + "#" + call.name + call.desc;
        if (knownMethods.contains(exact)) result.add(exact);
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE) {
            for (ClassNode type : classes) {
                String key = type.name + "#" + call.name + call.desc;
                if (knownMethods.contains(key) && subtype(type.name, call.owner, new HashSet<>())) result.add(key);
            }
        }
        return result;
    }

    private static Set<String> keys(BasicValue value) {
        return value instanceof Tagged tagged ? tagged.keys : Collections.emptySet();
    }

    private static BasicValue tag(BasicValue value, Collection<String> keys) {
        return value == null ? null : new Tagged(value.getType(), keys);
    }

    private static final class Tagged extends BasicValue {
        final Set<String> keys;
        Tagged(Type type, Collection<String> keys) { super(type); this.keys = new HashSet<>(keys); }
        @Override public boolean equals(Object other) { return other instanceof Tagged tagged && super.equals(other) && keys.equals(tagged.keys); }
        @Override public int hashCode() { return super.hashCode(); }
    }

    private final class Tracking extends BasicInterpreter {
        private final String method;
        Tracking(String method) { super(Opcodes.ASM9); this.method = method; }
        @Override public BasicValue newValue(Type type) { return tag(super.newValue(type), Set.of()); }
        @Override public BasicValue newParameterValue(boolean instance, int local, Type type) {
            return tag(super.newParameterValue(instance, local, type), parameters.getOrDefault(method + "@" + local, Set.of()));
        }
        @Override public BasicValue newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            BasicValue value = super.newOperation(instruction);
            Set<String> found = Set.of();
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                String symbol = namespaces.get(field.owner);
                found = symbol != null && (field.name.equals("Companion") || field.name.equals("INSTANCE")) ? Set.of(symbol + ":Regular")
                        : fields.getOrDefault(field.owner + "#" + field.name, Set.of());
            }
            return tag(value, found);
        }
        @Override public BasicValue copyOperation(AbstractInsnNode instruction, BasicValue value) { return value; }
        @Override public BasicValue unaryOperation(AbstractInsnNode instruction, BasicValue value) throws AnalyzerException {
            if (instruction instanceof FieldInsnNode field) {
                if (field.getOpcode() == Opcodes.PUTSTATIC) fields.computeIfAbsent(field.owner + "#" + field.name, k -> new HashSet<>()).addAll(keys(value));
                if (field.getOpcode() == Opcodes.GETFIELD) {
                    Set<String> captured = new HashSet<>(fields.getOrDefault(field.owner + "#" + field.name, Set.of()));
                    captured.addAll(fields.getOrDefault(field.owner + "#*", Set.of()));
                    captured.addAll(keys(value));
                    return tag(super.unaryOperation(instruction, value), captured);
                }
            }
            return tag(super.unaryOperation(instruction, value), keys(value));
        }
        @Override public BasicValue binaryOperation(AbstractInsnNode instruction, BasicValue first, BasicValue second) throws AnalyzerException {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                fields.computeIfAbsent(field.owner + "#" + field.name, k -> new HashSet<>()).addAll(keys(second));
            }
            return tag(super.binaryOperation(instruction, first, second), keys(first));
        }
        @Override public BasicValue ternaryOperation(AbstractInsnNode instruction, BasicValue first, BasicValue second, BasicValue third) throws AnalyzerException {
            if (instruction.getOpcode() == Opcodes.AASTORE && first instanceof Tagged tagged) tagged.keys.addAll(keys(third));
            return super.ternaryOperation(instruction, first, second, third);
        }
        @Override public BasicValue naryOperation(AbstractInsnNode instruction, List<? extends BasicValue> values) throws AnalyzerException {
            BasicValue result = super.naryOperation(instruction, values);
            Set<String> all = new HashSet<>();
            values.forEach(value -> all.addAll(keys(value)));
            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle handle) {
                    bind(handle.getOwner() + "#" + handle.getName() + handle.getDesc(), handle.getDesc(),
                            handle.getTag() == Opcodes.H_INVOKESTATIC, values);
                }
            }
            if (!(instruction instanceof MethodInsnNode call)) return tag(result, all);
            String owner = call.owner.endsWith("$Companion") ? call.owner.substring(0, call.owner.length() - 10) : call.owner;
            String symbol = namespaces.get(owner);
            Type returned = Type.getReturnType(call.desc);
            if (returned.getSort() == Type.OBJECT && namespaces.containsKey(returned.getInternalName())) {
                String selected = namespaces.get(returned.getInternalName());
                return tag(result, selected == null ? Set.of() : Set.of(selected + ":Regular"));
            }
            if (symbol != null && call.name.startsWith("get") && WEIGHTS.contains(call.name.substring(3))) {
                return tag(result, Set.of(symbol + ":" + call.name.substring(3)));
            }
            if ((call.owner.startsWith(API + "Packaged") || symbol != null) && call.name.equals("getAnimated")) {
                return tag(result, all.stream().map(key -> key + ":Animated").toList());
            }
            String target = call.owner + "#" + call.name + call.desc;
            Set<String> destinations = targets(call);
            bind(target, call.desc, call.getOpcode() == Opcodes.INVOKESTATIC, values);
            for (String destination : destinations) bind(destination, call.desc, call.getOpcode() == Opcodes.INVOKESTATIC, values);
            if (call.name.equals("<init>") && !all.isEmpty()) {
                fields.computeIfAbsent(call.owner + "#*", key -> new HashSet<>()).addAll(all);
                if (!values.isEmpty() && values.get(0) instanceof Tagged tagged) tagged.keys.addAll(all);
            }
            if (call.owner.startsWith(API) && (call.name.startsWith("SFSymbol") || call.name.startsWith("AnimatedSFSymbol") || call.name.startsWith("rememberSFSymbol"))) {
                used.addAll(all);
            } else if (call.owner.startsWith("java/util/") || call.owner.startsWith("kotlin/") || call.owner.startsWith("androidx/compose/runtime/")) {
                // Mutations and constructor captures share the same tracked container reference.
                if (!values.isEmpty() && values.get(0) instanceof Tagged tagged) tagged.keys.addAll(all);
            } else if (result == null && !call.name.equals("<init>") && destinations.isEmpty()) {
                // An opaque consumer may render its argument; retaining it is the safe boundary.
                used.addAll(all);
            }
            if (!destinations.isEmpty()) all.clear();
            all.addAll(returns.getOrDefault(target, Set.of()));
            for (String destination : destinations) all.addAll(returns.getOrDefault(destination, Set.of()));
            return tag(result, all);
        }
        private void bind(String target, String descriptor, boolean isStatic, List<? extends BasicValue> values) {
            int local = 0, value = 0;
            if (!isStatic && !values.isEmpty()) {
                parameters.computeIfAbsent(target + "@0", key -> new HashSet<>()).addAll(keys(values.get(value++)));
                local++;
            }
            for (Type type : Type.getArgumentTypes(descriptor)) {
                if (value >= values.size()) break;
                parameters.computeIfAbsent(target + "@" + local, key -> new HashSet<>()).addAll(keys(values.get(value++)));
                local += type.getSize();
            }
        }
        @Override public void returnOperation(AbstractInsnNode instruction, BasicValue value, BasicValue expected) {
            returns.computeIfAbsent(method, k -> new HashSet<>()).addAll(keys(value));
        }
        @Override public BasicValue merge(BasicValue first, BasicValue second) {
            Set<String> merged = new HashSet<>(keys(first));
            merged.addAll(keys(second));
            return tag(super.merge(first, second), merged);
        }
    }
}
