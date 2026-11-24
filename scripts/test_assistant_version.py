"""Exercise the app's real signature checks with class files, without Android."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/io/github/xiaoaiswitch/AssistantVersion.java"
HARNESS = """
package io.github.xiaoaiswitch;

public class CompatibilityTest {
    public static class Event {}
    public static class Instruction {}
    public static class Handler {}
    public static class Context {}
    public static class Exact {
        static { if (true) throw new AssertionError("probing initialized a class"); }
        Handler y;
        boolean post(Event event) { return false; }
        void receive(Instruction instruction) {}
        void deliver(Instruction instruction) {}
    }
    public static class WrongReturn {
        Handler y;
        Boolean post(Event event) { return false; }
        void receive(Instruction instruction) {}
        void deliver(Instruction instruction) {}
    }
    public static class WrongField {
        Object y;
        boolean post(Event event) { return false; }
        void receive(Instruction instruction) {}
        void deliver(Instruction instruction) {}
    }
    public static class StaticField {
        static Handler y;
        boolean post(Event event) { return false; }
        void receive(Instruction instruction) {}
        void deliver(Instruction instruction) {}
    }
    public static class StaticMethod {
        Handler y;
        static boolean post(Event event) { return false; }
        void receive(Instruction instruction) {}
        void deliver(Instruction instruction) {}
    }
    public static class WrongParameter {
        Handler y;
        boolean post(Event event) { return false; }
        void receive(Object instruction) {}
        void deliver(Instruction instruction) {}
    }
    public static class Inherited extends Exact {}
    public interface VoiceCallback {}
    public static class RenamedVoice {
        static final RenamedVoice u = null;
        String speakTts(String text, VoiceCallback listener, boolean enable) { return text; }
    }
    public static class RenamedThinking {
        static Object generateDialogStateContext() { return null; }
    }

    private static boolean matches(String owner) {
        return new AssistantVersion.Engine(CompatibilityTest.class.getName() + "$" + owner,
                "post", "receive", "deliver", "y", "", "", "")
                .matches(CompatibilityTest.class.getClassLoader(), Event.class, Instruction.class, Handler.class);
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        check(matches("Exact"), "exact declared instance members should match without initialization");
        for (String rejected : args) check(!matches(rejected), rejected + " matched");
        AssistantVersion old = AssistantVersion.forCode(507011020);
        AssistantVersion current = AssistantVersion.forCode(508002070);
        check(old != null && old.phoneControl(), "legacy route removed");
        check(current != null && current.phoneControl() && current.controlBackend() != null, "8.2 native control backend is missing");
        check(old.controlBackend() == null, "legacy control should keep its original backend");
        check(AssistantVersion.forCode(508002071) == null, "nearby build mislabeled as an exact match");
        ClassLoader loader = CompatibilityTest.class.getClassLoader();
        var compatible = AssistantVersion.resolve(508002071L, "8.2.71.0", loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(compatible != null && !compatible.exact(), "a new build with matching core symbols did not adapt");
        check(compatible.adapter() == current, "the wrong protocol family was selected");
        check(compatible.features().equals(java.util.Set.of(AssistantVersion.Feature.QUERY_CARD)),
                "8.x did not recognize its independent query-card capability or enabled unchecked hooks");
        var published = AssistantVersion.resolve(508002080L, "RELEASE-8.2.80.10019", loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(published != null && published.adapter() == current && !published.supports(AssistantVersion.Feature.CONTROL),
                "published 8.x version should select its family without enabling unverified hooks");
        var legacy = AssistantVersion.resolve(507011021L, "7.11.21", loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(legacy != null && !legacy.exact() && legacy.adapter() == old, "7.x same-structure build did not adapt");
        check(legacy.features().equals(java.util.Set.of(AssistantVersion.Feature.VOICE)),
                "7.x did not independently detect its voice backend");
        var labeledLegacy = AssistantVersion.resolve(507013035L, " release-7.13.35.0012-CN ", loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(labeledLegacy != null && labeledLegacy.adapter() == old, "7.x distributor label prevented family selection");
        check(AssistantVersion.resolve(508002071L, "8.placeholder", loader,
                Event.class, Instruction.class, Handler.class, Context.class) == null, "non-version label enabled the 8.x profile");
        var exact = AssistantVersion.resolve(508002070L, "8.2.70.8421", loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(exact != null && exact.exact() && exact.supports(AssistantVersion.Feature.CONTROL),
                "verified profile lost its native control support");
        var renamed = new AssistantVersion.Engine(Exact.class.getName(), "post", "receive", "deliver", "y", "p31.g", "", "");
        var dynamic = AssistantVersion.resolveDiscovered("8.2.81.1", renamed, java.util.Map.of(), false, loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(dynamic != null && dynamic.discovered() && dynamic.adapter().engine().type().equals(renamed.type())
                && dynamic.features().equals(java.util.Set.of(AssistantVersion.Feature.QUERY_CARD)), "discovery lost an independently verified feature");
        check(AssistantVersion.resolveDiscovered("8.2.81.1", renamed, java.util.Map.of(), false, loader,
                Event.class, null, Handler.class, Context.class) == null, "discovery skipped runtime signature checks");
        var reused = AssistantVersion.resolveDiscovered("8.2.81.1", renamed, java.util.Map.of(), true, loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(reused != null && reused.supports(AssistantVersion.Feature.CONTROL)
                && reused.adapter().controlBackend().equals(current.controlBackend()), "only the reviewed native control backend should be reused");
        var legacyReuse = AssistantVersion.resolveDiscovered("7.13.30", renamed, java.util.Map.of(), true, loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(legacyReuse != null && !legacyReuse.supports(AssistantVersion.Feature.CONTROL), "unreviewed legacy version enabled control");
        var relocatedFeatures = AssistantVersion.resolveDiscovered("7.13.30", renamed, java.util.Map.of(
                "tts", RenamedVoice.class.getName(), "ttsCallback", VoiceCallback.class.getName(), "ttsSingleton", "u",
                "dialogContext", RenamedThinking.class.getName()), false, loader,
                Event.class, Instruction.class, Handler.class, Context.class);
        check(relocatedFeatures != null && relocatedFeatures.features().equals(java.util.Set.of(
                AssistantVersion.Feature.VOICE, AssistantVersion.Feature.THINKING)), "renamed feature groups were not independently checked");
        check(AssistantVersion.resolve(508002071L, "9.0.1", loader,
                Event.class, Instruction.class, Handler.class, Context.class) == null, "9.x was treated as 8.x");
        check(AssistantVersion.resolve(508002071L, "8.2.71", loader,
                Event.class, null, Handler.class, Context.class) == null, "missing protocol dependency was ignored");
        check(AssistantVersion.resolve(508002071L, "8.2.71", loader,
                Event.class, Instruction.class, Object.class, Context.class) == null, "wrong handler shape was ignored");
        ClassLoader missingLegacy = new ClassLoader(loader) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("b30.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        check(AssistantVersion.resolve(507011021L, "7.11.21", missingLegacy,
                Event.class, Instruction.class, Handler.class, Context.class) == null, "a 7.x app reused an 8.x structure");
        ClassLoader missingSession = new ClassLoader(loader) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("p31.g")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        check(AssistantVersion.resolve(508002071L, "8.2.71", missingSession,
                Event.class, Instruction.class, Handler.class, Context.class) == null, "missing session lifecycle was ignored");
        ClassLoader missingReply = new ClassLoader(loader) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.xiaomi.ai.api.common.APIUtils")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        check(AssistantVersion.resolve(508002071L, "8.2.71", missingReply,
                Event.class, Instruction.class, Handler.class, Context.class) == null, "missing native reply decoder was ignored");
    }
}
"""
KNOWN_8_SESSION = """
package p31;

public class g {
    public void clearSession() {}
}
"""
KNOWN_8_ENGINE = """
package p31;

import io.github.xiaoaiswitch.CompatibilityTest.Event;
import io.github.xiaoaiswitch.CompatibilityTest.Handler;
import io.github.xiaoaiswitch.CompatibilityTest.Instruction;

public class o0 {
    Handler y;
    boolean D0(Event event) { return false; }
    void H0(Instruction instruction) {}
    void b0(Instruction instruction) {}
}
"""


SHAPES = {
    "p31/o0.java": KNOWN_8_ENGINE,
    "p31/g.java": KNOWN_8_SESSION,
    "p31/y0.java": "package p31; public class y0 { static boolean shouldAddQueryCard(String id) { return true; } }",
    "b30/o0.java": (KNOWN_8_ENGINE.replace("package p31;", "package b30;")
                   .replace("D0(", "u0(").replace("H0(", "y0(").replace("b0(", "W(")),
    "b30/g.java": KNOWN_8_SESSION.replace("package p31;", "package b30;"),
    "s10/b.java": "package s10; public interface b {}",
    "b30/z0.java": """package b30; public class z0 {
        static final z0 a = null;
        String speakTts(String query, s10.b callback, boolean flag) { return query; }
    }""",
    "com/xiaomi/ai/api/common/APIUtils.java": """package com.xiaomi.ai.api.common;
        import io.github.xiaoaiswitch.CompatibilityTest.Instruction;
        public class APIUtils { public static Instruction readInstruction(String json) { return null; } }""",
}


class AssistantVersionTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK unavailable")
    def test_only_exact_instance_members_match_without_initializing_target(self):
        with tempfile.TemporaryDirectory() as path:
            temp = Path(path)
            harness = temp / "CompatibilityTest.java"
            harness.write_text(HARNESS, encoding="utf-8")
            sources = [SOURCE, harness]
            for name, code in SHAPES.items():
                fixture = temp / name
                fixture.parent.mkdir(parents=True, exist_ok=True)
                fixture.write_text(code, encoding="utf-8")
                sources.append(fixture)
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", temp, *sources], check=True)
            subprocess.run(
                ["java", "-classpath", temp, "io.github.xiaoaiswitch.CompatibilityTest", "Missing", "WrongReturn",
                 "WrongField", "StaticField", "StaticMethod", "WrongParameter", "Inherited"],
                check=True,
            )


if __name__ == "__main__":
    unittest.main()
