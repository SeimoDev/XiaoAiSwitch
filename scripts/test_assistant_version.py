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
        check(AssistantVersion.forCode(508002071) == null, "nearby unknown version enabled");
        ClassLoader loader = CompatibilityTest.class.getClassLoader();
        check(AssistantVersion.detectedStructure(loader, Event.class, Instruction.class, Handler.class)
                .equals("匹配 8.2 结构"), "known structure was not detected");
        check(AssistantVersion.detectedStructure(loader, null, Instruction.class, Handler.class)
                .equals("未匹配"), "missing protocol class was accepted");
    }
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


class AssistantVersionTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK unavailable")
    def test_only_exact_instance_members_match_without_initializing_target(self):
        with tempfile.TemporaryDirectory() as path:
            temp = Path(path)
            harness = temp / "CompatibilityTest.java"
            candidate = temp / "o0.java"
            harness.write_text(HARNESS, encoding="utf-8")
            candidate.write_text(KNOWN_8_ENGINE, encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", temp, SOURCE, harness, candidate], check=True)
            subprocess.run(
                ["java", "-classpath", temp, "io.github.xiaoaiswitch.CompatibilityTest", "Missing", "WrongReturn",
                 "WrongField", "StaticField", "StaticMethod", "WrongParameter", "Inherited"],
                check=True,
            )


if __name__ == "__main__":
    unittest.main()
