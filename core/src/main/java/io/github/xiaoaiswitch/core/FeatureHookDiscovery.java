package io.github.xiaoaiswitch.core;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Rebinds only previously inspected feature interfaces, using names only for stable public protocol types. */
public final class FeatureHookDiscovery {
    private FeatureHookDiscovery() {}

    record Rule(String role, String shape, String body, String kind, String member) {
        String line() { return String.join("\t", role, shape, body, kind, member); }
    }

    public static Map<String, String> find(int family, List<File> apks) throws IOException {
        if (family != 7 && family != 8) return Map.of();
        try (var resource = FeatureHookDiscovery.class.getResourceAsStream("hook-layout-" + family + ".tsv")) {
            if (resource == null) return Map.of();
            var rules = new ArrayList<Rule>();
            try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    var parts = line.split("\t", -1);
                    if (parts.length == 5) rules.add(new Rule(parts[0], parts[1], parts[2], parts[3], parts[4]));
                }
            }
            return find(apks, rules);
        }
    }

    static Map<String, String> find(List<File> apks, List<Rule> rules) throws IOException {
        if (rules.isEmpty()) return Map.of();
        var byShape = new HashMap<String, List<Rule>>();
        for (Rule rule : rules) byShape.computeIfAbsent(rule.shape(), ignored -> new ArrayList<>()).add(rule);
        var result = new HashMap<String, String>();
        var ambiguous = new HashSet<String>();
        if (!DialogueHookDiscovery.visit(apks, type -> {
            var choices = byShape.get(shape(type));
            if (choices == null) return;
            String body = body(type);
            for (Rule rule : choices) {
                if (!body.equals(rule.body()) || ambiguous.contains(rule.role())) continue;
                String value = switch (rule.kind()) {
                    case "c" -> className(type.getType());
                    case "m" -> method(type, rule.member());
                    case "f" -> field(type, rule.member());
                    default -> null;
                };
                if (value != null && result.putIfAbsent(rule.role(), value) != null) ambiguous.add(rule.role());
            }
        })) return Map.of();
        ambiguous.forEach(result::remove);
        return Collections.unmodifiableMap(result);
    }

    static List<Rule> rulesFor(List<File> apks, Map<String, String> symbols) throws IOException {
        var byType = new HashMap<String, List<Map.Entry<String, String>>>();
        for (var symbol : symbols.entrySet()) {
            String owner = symbol.getValue().split("#", 2)[0];
            byType.computeIfAbsent("L" + owner.replace('.', '/') + ";", ignored -> new ArrayList<>()).add(symbol);
        }
        var result = new ArrayList<Rule>();
        if (!DialogueHookDiscovery.visit(apks, type -> {
            var relevant = byType.get(type.getType());
            if (relevant == null) return;
            String shape = shape(type);
            String body = body(type);
            for (var symbol : relevant) {
                var parts = symbol.getValue().split("#", 2);
                if (parts.length == 1) {
                    result.add(new Rule(symbol.getKey(), shape, body, "c", "-"));
                    continue;
                }
                var selector = parts[1].split(":", 3);
                var matches = new ArrayList<String>();
                if (selector[0].equals("m")) for (Method method : type.getMethods()) {
                    if (method.getName().equals(selector[1])
                            && (selector.length == 2 || method.getParameterTypes().size() == Integer.parseInt(selector[2]))) {
                        matches.add(methodBody(type.getType(), method));
                    }
                }
                if (selector[0].equals("f")) for (Field field : type.getFields()) {
                    if (field.getName().equals(selector[1])) matches.add(fieldShape(type.getType(), field));
                }
                if (matches.size() != 1) throw new IllegalArgumentException("Expected a unique reference for " + symbol.getKey());
                result.add(new Rule(symbol.getKey(), shape, body, selector[0], matches.get(0)));
            }
        }) || result.size() != symbols.size()) throw new IllegalArgumentException("Reference APK is missing feature symbols");
        result.sort(java.util.Comparator.comparing(Rule::role));
        return result;
    }

    private static String shape(ClassDef clazz) {
        String owner = clazz.getType();
        var entries = new ArrayList<String>();
        for (Field field : clazz.getFields()) entries.add("f:" + fieldShape(owner, field));
        for (Method method : clazz.getMethods()) entries.add("m:" + method.getAccessFlags() + ":" + methodShape(owner, method));
        for (String iface : clazz.getInterfaces()) entries.add("i:" + normalized(owner, iface));
        entries.add("class:" + clazz.getAccessFlags() + ":" + normalized(owner, clazz.getSuperclass()));
        Collections.sort(entries);
        return DialogueHookDiscovery.checksum(String.join("\n", entries));
    }

    private static String body(ClassDef clazz) {
        var entries = new ArrayList<String>();
        for (Method method : clazz.getMethods()) entries.add(methodBody(clazz.getType(), method));
        Collections.sort(entries);
        return DialogueHookDiscovery.checksum(String.join("\n", entries));
    }

    private static String methodBody(String owner, Method method) {
        var key = new StringBuilder(methodShape(owner, method));
        var implementation = method.getImplementation();
        if (implementation != null) for (var instruction : implementation.getInstructions()) {
            key.append('\n').append(instruction.getOpcode().name().replace("_JUMBO", "").replace("_RANGE", ""));
            if (!(instruction instanceof ReferenceInstruction ref)) continue;
            if (ref.getReference() instanceof MethodReference call) {
                key.append(':').append(normalized(owner, call.getDefiningClass())).append(':').append(methodShape(owner, call));
            } else if (ref.getReference() instanceof FieldReference field) {
                key.append(':').append(normalized(owner, field.getDefiningClass())).append(':')
                        .append(name(field.getName())).append(':').append(normalized(owner, field.getType()));
            } else if (ref.getReference() instanceof TypeReference type) {
                key.append(':').append(normalized(owner, type.getType()));
            } else if (ref.getReference() instanceof StringReference text) {
                key.append(':').append(text.getString().replace(className(owner), "@"));
            }
        }
        return DialogueHookDiscovery.checksum(key.toString());
    }

    private static String method(ClassDef owner, String expected) {
        String found = null;
        for (Method method : owner.getMethods()) {
            if (!methodBody(owner.getType(), method).equals(expected)) continue;
            if (found != null) return null;
            found = method.getName();
        }
        return found;
    }

    private static String field(ClassDef owner, String expected) {
        String found = null;
        for (Field field : owner.getFields()) {
            if (!fieldShape(owner.getType(), field).equals(expected)) continue;
            if (found != null) return null;
            found = field.getName();
        }
        return found;
    }

    private static String methodShape(String owner, MethodReference method) {
        var key = new StringBuilder(name(method.getName())).append('(');
        for (var parameter : method.getParameterTypes()) key.append(normalized(owner, parameter.toString())).append(',');
        return key.append(')').append(normalized(owner, method.getReturnType())).toString();
    }

    private static String fieldShape(String owner, Field field) {
        return field.getAccessFlags() + ":" + name(field.getName()) + ":" + normalized(owner, field.getType());
    }

    private static String normalized(String owner, String type) {
        if (type == null) return "-";
        if (type.equals(owner)) return "@";
        if (type.startsWith("[")) return "[" + normalized(owner, type.substring(1));
        if (!type.startsWith("L") || type.startsWith("Landroid/") || type.startsWith("Ljava/")
                || type.startsWith("Lcom/xiaomi/ai/api/")) return type;
        return "?";
    }

    private static String name(String value) { return value.length() <= 3 ? "?" : value; }
    private static String className(String type) { return type.substring(1, type.length() - 1).replace('/', '.'); }
}
