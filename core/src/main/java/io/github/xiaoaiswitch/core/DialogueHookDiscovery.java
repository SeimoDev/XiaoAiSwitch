package io.github.xiaoaiswitch.core;

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves only the existing text-dialogue protocol, without loading target classes. */
public final class DialogueHookDiscovery {
    private static final String EVENT = "Lcom/xiaomi/ai/api/common/Event;";
    private static final String INSTRUCTION = "Lcom/xiaomi/ai/api/common/Instruction;";
    private static final Pattern DEX_ENTRY = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    private static final Pattern MEMBER = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");
    private static final Pattern CLASS = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*");
    private static final long MAX_DEX = 64L << 20;

    private DialogueHookDiscovery() {}

    public record Layout(String owner, String post, String receive, String deliver, String handler, String session) {
        public String encode() { return String.join("\n", owner, post, receive, deliver, handler, session); }

        public static Layout decode(String value) {
            if (value == null || value.length() > 2048) return null;
            String[] parts = value.split("\n", -1);
            if (parts.length != 6 || !CLASS.matcher(parts[0]).matches() || !CLASS.matcher(parts[5]).matches()) return null;
            for (int index = 1; index <= 4; index++) if (!MEMBER.matcher(parts[index]).matches()) return null;
            if (parts[2].equals(parts[3])) return null;
            return new Layout(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
        }
    }

    private record Engine(String type, String post, String receive, String deliver, String handler, Set<String> sessions) {}
    private record Evidence(Method method, Set<String> strings, List<MethodReference> calls) {}

    public static Layout find(List<File> apks) throws IOException {
        var engines = new ArrayList<Engine>();
        var sessions = new HashMap<String, Integer>();
        for (File apk : apks) {
            try (var zip = new ZipFile(apk)) {
                for (ZipEntry entry : dexEntries(zip)) {
                    if (entry.getSize() <= 0 || entry.getSize() > MAX_DEX) return null;
                    try (var input = new BufferedInputStream(zip.getInputStream(entry))) {
                        var dex = DexBackedDexFile.fromInputStream(null, input);
                        for (ClassDef type : dex.getClasses()) {
                            if (isSession(type)) sessions.merge(type.getType(), 1, Integer::sum);
                            Engine engine = engine(type);
                            if (engine != null) engines.add(engine);
                        }
                    }
                }
            }
        }
        Layout found = null;
        for (Engine engine : engines) {
            var related = engine.sessions().stream().filter(type -> sessions.getOrDefault(type, 0) == 1).collect(Collectors.toList());
            if (related.size() != 1) continue;
            if (found != null) return null;
            found = new Layout(className(engine.type()), engine.post(), engine.receive(), engine.deliver(),
                    engine.handler(), className(related.get(0)));
        }
        return found == null ? null : Layout.decode(found.encode());
    }

    public static String fingerprint(long versionCode, long updatedAt, List<File> apks) throws IOException {
        var identity = new StringBuilder().append(versionCode).append(':').append(updatedAt);
        for (File apk : apks) {
            identity.append('\n').append(apk.getName()).append(':').append(apk.length());
            try (var zip = new ZipFile(apk)) {
                for (ZipEntry entry : dexEntries(zip)) {
                    identity.append('\n').append(entry.getName()).append(':').append(entry.getSize()).append(':').append(entry.getCrc());
                }
            }
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        var hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(Character.forDigit((value >>> 4) & 0xf, 16)).append(Character.forDigit(value & 0xf, 16));
        return hex.toString();
    }

    private static List<? extends ZipEntry> dexEntries(ZipFile zip) {
        return zip.stream().filter(entry -> DEX_ENTRY.matcher(entry.getName()).matches())
                .sorted(Comparator.comparing(ZipEntry::getName)).collect(Collectors.toList());
    }

    private static Engine engine(ClassDef type) {
        String handler = null;
        for (var field : type.getInstanceFields()) {
            if (!field.getType().equals("Landroid/os/Handler;")) continue;
            if (handler != null) return null;
            handler = field.getName();
        }
        if (handler == null) return null;
        var eventMethods = new ArrayList<Method>();
        var instructionMethods = new ArrayList<Method>();
        for (Method method : type.getMethods()) {
            if (!instance(method)) continue;
            if (shape(method, "Z", EVENT)) eventMethods.add(method);
            if (shape(method, "V", INSTRUCTION)) instructionMethods.add(method);
        }
        if (eventMethods.isEmpty() || instructionMethods.size() < 2) return null;
        var posts = eventMethods.stream().map(DialogueHookDiscovery::evidence)
                .filter(item -> item.strings().contains("postEvent: engine is null") && item.calls().stream()
                        .anyMatch(call -> !call.getDefiningClass().equals(type.getType())
                                && call.getName().equals("postEvent") && shape(call, "Z", EVENT))).collect(Collectors.toList());
        if (posts.size() != 1) return null;
        var receives = instructionMethods.stream().map(DialogueHookDiscovery::evidence)
                .filter(item -> item.strings().contains("Error processing instruction: ")).collect(Collectors.toList());
        if (receives.size() != 1) return null;
        Evidence receive = receives.get(0);
        var deliveries = receive.calls().stream().filter(call -> call.getDefiningClass().equals(type.getType())
                        && !call.getName().equals(receive.method().getName()) && shape(call, "V", INSTRUCTION)
                        && instructionMethods.stream().anyMatch(method -> method.getName().equals(call.getName())))
                .map(MethodReference::getName).distinct().collect(Collectors.toList());
        if (deliveries.size() != 1) return null;
        var related = new HashSet<String>();
        for (Method method : type.getMethods()) {
            for (MethodReference call : evidence(method).calls()) {
                if (call.getName().equals("getInstance") && shape(call, call.getDefiningClass())) related.add(call.getDefiningClass());
            }
        }
        return new Engine(type.getType(), posts.get(0).method().getName(), receive.method().getName(), deliveries.get(0), handler, related);
    }

    private static boolean isSession(ClassDef type) {
        boolean clear = false;
        boolean delivery = false;
        boolean instance = false;
        for (Method method : type.getMethods()) {
            if (method.getName().equals("clearSession") && instance(method) && shape(method, "V")) clear = true;
            if (method.getName().equals("onNlpResult") && instance(method) && shape(method, "V", "[" + INSTRUCTION, "Z")) delivery = true;
            if (method.getName().equals("getInstance") && Modifier.isStatic(method.getAccessFlags())
                    && shape(method, type.getType())) instance = true;
            if (clear && delivery && instance) return true;
        }
        return false;
    }

    private static Evidence evidence(Method method) {
        var strings = new HashSet<String>();
        var calls = new ArrayList<MethodReference>();
        var body = method.getImplementation();
        if (body != null) {
            for (var instruction : body.getInstructions()) {
                if (!(instruction instanceof ReferenceInstruction reference)) continue;
                if (reference.getReference() instanceof StringReference string) strings.add(string.getString());
                if (reference.getReference() instanceof MethodReference call && instruction.getOpcode().name().startsWith("INVOKE_")) calls.add(call);
            }
        }
        return new Evidence(method, strings, calls);
    }

    private static boolean instance(Method method) {
        return (method.getAccessFlags() & (Modifier.STATIC | Modifier.NATIVE | Modifier.ABSTRACT)) == 0;
    }

    private static boolean shape(MethodReference method, String result, String... arguments) {
        var parameters = method.getParameterTypes();
        if (!method.getReturnType().equals(result) || parameters.size() != arguments.length) return false;
        for (int index = 0; index < arguments.length; index++) if (!arguments[index].contentEquals(parameters.get(index))) return false;
        return true;
    }

    private static String className(String descriptor) { return descriptor.substring(1, descriptor.length() - 1).replace('/', '.'); }
}
