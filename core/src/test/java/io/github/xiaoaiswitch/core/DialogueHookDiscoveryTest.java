package io.github.xiaoaiswitch.core;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class DialogueHookDiscoveryTest {
    private static final String EVENT = "Lcom/xiaomi/ai/api/common/Event;";
    private static final String INSTRUCTION = "Lcom/xiaomi/ai/api/common/Instruction;";
    private static final String SESSION = "Lrenamed/session/O;";

    @Rule public TemporaryFolder folders = new TemporaryFolder();

    @Test public void findsRenamedEngineAndSessionAcrossInstalledSplits() throws Exception {
        File base = apk("base.apk", "classes3.dex", List.of(engine("Lrenamed/engine/A;", true)));
        File split = apk("split.apk", "classes.dex", List.of(session()));
        var layout = DialogueHookDiscovery.find(List.of(base, split));
        assertEquals(new DialogueHookDiscovery.Layout("renamed.engine.A", "a", "b", "c", "k", "renamed.session.O"), layout);
        assertEquals(layout, DialogueHookDiscovery.Layout.decode(layout.encode()));
        assertNull(DialogueHookDiscovery.Layout.decode("renamed.engine.A"));
        assertNotEquals(DialogueHookDiscovery.fingerprint(1, 5, List.of(base, split)),
                DialogueHookDiscovery.fingerprint(1, 6, List.of(base, split)));
        assertNotEquals(DialogueHookDiscovery.fingerprint(1, 5, List.of(base, split)),
                DialogueHookDiscovery.fingerprint(1, 5, List.of(base)));
    }

    @Test public void declinesTwoPlausibleMappingsOrIncompleteEvidence() throws Exception {
        File ambiguous = apk("ambiguous.apk", "classes.dex", List.of(engine("Lrenamed/engine/A;", true),
                engine("Lrenamed/engine/B;", true), session()));
        assertNull(DialogueHookDiscovery.find(List.of(ambiguous)));
        File missingRelation = apk("missing.apk", "classes.dex", List.of(engine("Lrenamed/engine/A;", false), session()));
        assertNull(DialogueHookDiscovery.find(List.of(missingRelation)));
        File missingSession = apk("missing-session.apk", "classes.dex", List.of(engine("Lrenamed/engine/A;", true)));
        assertNull(DialogueHookDiscovery.find(List.of(missingSession)));
    }

    @Test public void featureFingerprintSurvivesRenamesWithoutAcceptingChangedOrAmbiguousCode() throws Exception {
        var reference = apk("original.apk", "classes.dex", List.of(preview("Loriginal/Q;", "g", "preview")));
        var rules = FeatureHookDiscovery.rulesFor(List.of(reference), Map.of("gallery", "original.Q", "galleryOpen", "original.Q#m:g"));
        var renamed = apk("renamed.apk", "classes2.dex", List.of(preview("Lrelocated/B;", "oo", "preview")));
        assertEquals(Map.of("gallery", "relocated.B", "galleryOpen", "oo"), FeatureHookDiscovery.find(List.of(renamed), rules));
        var changed = apk("changed.apk", "classes.dex", List.of(preview("Lrelocated/B;", "oo", "unrelated")));
        assertEquals(Map.of(), FeatureHookDiscovery.find(List.of(changed), rules));
        var ambiguous = apk("two.apk", "classes.dex", List.of(preview("Lrelocated/B;", "oo", "preview"),
                preview("Lrelocated/C;", "l", "preview")));
        assertEquals(Map.of(), FeatureHookDiscovery.find(List.of(ambiguous), rules));
    }

    private static ImmutableClassDef preview(String owner, String name, String text) {
        return clazz(owner, List.of(), List.of(method(owner, name, List.of("I"), "Ljava/lang/String;", 0,
                marker(text), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))));
    }

    private File apk(String name, String dexPath, List<ImmutableClassDef> classes) throws Exception {
        var file = folders.newFile(name);
        var dex = new MemoryDataStore();
        DexPool.writeTo(dex, new ImmutableDexFile(Opcodes.getDefault(), classes));
        try (var zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(dexPath));
            zip.write(dex.getData());
        } finally {
            dex.close();
        }
        return file;
    }

    private static ImmutableClassDef engine(String owner, boolean dispatches) {
        return clazz(owner, List.of(new ImmutableField(owner, "k", "Landroid/os/Handler;", Modifier.PUBLIC, null, Set.of(), Set.of())), List.of(
                method(owner, "a", List.of(EVENT), "Z", 0, marker("postEvent: engine is null"),
                        call("Ltest/engine/Upstream;", "postEvent", List.of(EVENT), "Z", false), new ImmutableInstruction11x(Opcode.RETURN, 0)),
                method(owner, "b", List.of(INSTRUCTION), "V", 0, marker("Error processing instruction: "),
                        call(owner, dispatches ? "c" : "unrelated", List.of(INSTRUCTION), "V", false), end()),
                method(owner, "c", List.of(INSTRUCTION), "V", 0, end()),
                method(owner, "l", List.of(), "V", 0, call(SESSION, "getInstance", List.of(), SESSION, true), end())));
    }

    private static ImmutableClassDef session() {
        return clazz(SESSION, List.of(), List.of(
                method(SESSION, "clearSession", List.of(), "V", 0, end()),
                method(SESSION, "onNlpResult", List.of("[" + INSTRUCTION, "Z"), "V", 0, end()),
                method(SESSION, "getInstance", List.of(), SESSION, Modifier.STATIC, new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))));
    }

    private static ImmutableClassDef clazz(String type, List<ImmutableField> fields, List<ImmutableMethod> methods) {
        return new ImmutableClassDef(type, Modifier.PUBLIC, "Ljava/lang/Object;", List.of(), null, Set.of(), fields, methods);
    }

    private static ImmutableMethod method(String owner, String name, List<String> args, String result, int flags, Instruction... instructions) {
        var parameters = new ArrayList<ImmutableMethodParameter>();
        for (String arg : args) parameters.add(new ImmutableMethodParameter(arg, Set.of(), null));
        return new ImmutableMethod(owner, name, parameters, result, Modifier.PUBLIC | flags, Set.of(), Set.of(),
                new ImmutableMethodImplementation(4, List.of(instructions), List.of(), List.of()));
    }

    private static Instruction marker(String text) { return new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(text)); }

    private static Instruction call(String owner, String name, List<String> args, String result, boolean isStatic) {
        var method = new ImmutableMethodReference(owner, name, args, result);
        return new ImmutableInstruction35c(isStatic ? Opcode.INVOKE_STATIC : Opcode.INVOKE_VIRTUAL,
                args.size() + (isStatic ? 0 : 1), 0, 1, 0, 0, 0, method);
    }

    private static Instruction end() { return new ImmutableInstruction10x(Opcode.RETURN_VOID); }
}
