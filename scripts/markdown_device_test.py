"""Verify module-owned Toast cards use the shipped native Markdown renderer."""
import argparse
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from fixture_provider import MARKDOWN_REPLY

ROOT = Path(__file__).resolve().parents[1]
APP = "io.github.xiaoaiswitch"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--records", default=str(ROOT / "evidence/switch-home-regression.jsonl"))
    args = parser.parse_args()
    directory = ROOT / "evidence"
    prefix = ["adb", "-s", args.serial]

    def adb(*command):
        return subprocess.run(prefix + list(command), capture_output=True, check=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def capture(name):
        adb("shell", "input", "keyevent", "224")
        adb("shell", "uiautomator dump /sdcard/xa-markdown.xml")
        xml = adb("shell", "cat /sdcard/xa-markdown.xml")
        directory.joinpath(name + ".xml").write_bytes(xml)
        directory.joinpath(name + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        return ET.fromstring(xml)

    records = Path(args.records)
    before = len(records.read_text(encoding="utf8").splitlines()) if records.exists() else 0
    backup = root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml")
    config = {"enabled": True, "revision": int(time.time()*1000), "defaultRoute": "chat", "autoRoute": False,
              "chat": {"enabled": True, "endpoint": "http://127.0.0.1:8765/v1", "token": "FIXTURE_TOKEN",
                       "model": "FIXTURE_MARKDOWN", "systemPrompt": "FIXTURE_MARKDOWN_PROMPT"}}
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps(config)
    path = directory / "markdown-prefs.xml"
    ET.ElementTree(prefs).write(path, encoding="utf8", xml_declaration=True)
    try:
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("shell", "am", "force-stop", APP)
        adb("push", str(path), "/data/local/tmp/xa-markdown-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xa-markdown-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        root("am start -W -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        pid = adb("shell", "pidof", "com.miui.voiceassist").decode().strip().split()[0]
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService "
             "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
             "--ez use_offline_nlp false --ez isCloseMic true --es assist_query MARKDOWN_FIXTURE")
        deadline = time.monotonic() + 35
        rendered = []
        while time.monotonic() < deadline:
            data = adb("logcat", "-d", "-v", "brief", "--pid=" + pid, "-s", "XiaoAiSwitch:I").decode("utf8", errors="replace")
            rendered = [line for line in data.splitlines() if "markdown-rendered id=xiaoaiswitch-markdown-" in line]
            if rendered:
                break
            time.sleep(.3)
        assert rendered, "Native Markdown renderer did not produce span evidence"
        assert int(re.search(r"spans=(\d+)", rendered[-1]).group(1)) >= 5, rendered[-1]
        types = set(rendered[-1].split("types=", 1)[1].split(","))
        assert {"b60.a", "hw0.a", "hw0.b", "hw0.c", "h60.c", "io.noties.markwon.core.spans.LinkSpan"} <= types, "Native block styles are missing"
        tree = capture("markdown-native")
        text = "\n".join(n.get("text", "") for n in tree.iter("node"))
        assert "MD_HEADING" in text and "MD_BOLD" in text and "MD_ITEM_ONE" in text, text
        assert "# MD_HEADING" not in text and "**MD_BOLD**" not in text and "```python" not in text, "Markdown syntax is displayed literally"
        requests = [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()[before:]]
        assert requests and requests[0]["body"]["model"] == "FIXTURE_MARKDOWN" and requests[0]["fixture_token_matches"]
        checkpoint = len(records.read_text(encoding="utf8").splitlines())
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService "
             "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
             "--ez use_offline_nlp false --ez isCloseMic true --es assist_query PLAIN_RENDER_REGRESSION")
        deadline = time.monotonic() + 15
        following = []
        while time.monotonic() < deadline:
            following = [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()[checkpoint:]]
            if following:
                break
            time.sleep(.2)
        assert following, "Follow-up request is missing"
        assert any(m.get("role") == "assistant" and m.get("content") == MARKDOWN_REPLY
                   for m in following[0]["body"]["messages"]), "UI formatting modified the model-visible Markdown history"
        time.sleep(1)
        plain = capture("markdown-plain-rebind")
        assert any(n.get("text") == "XASWITCH_FIXTURE_OK" for n in plain.iter("node")), "Plain follow-up reply changed"
        # The HTTP fixture supplies the real complete Markdown; UI output must differ by native formatting only.
        directory.joinpath("markdown-source.md").write_text(MARKDOWN_REPLY, encoding="utf8")
        result = {"passed": True, "native_renderer": "nj0.c / ew0.e", "scope": "module-owned Toast instruction IDs",
                  "span_evidence": rendered[-1], "raw_markdown_history_preserved": True, "same_process_plain_reply": True}
        directory.joinpath("markdown-native-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf8")
        print(json.dumps(result, ensure_ascii=False))
    finally:
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("shell", "am", "force-stop", APP)
        path.write_bytes(backup)
        adb("push", str(path), "/data/local/tmp/xa-markdown-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xa-markdown-prefs.xml", "shared_prefs/switch.xml")
        adb("shell", "rm", "/data/local/tmp/xa-markdown-prefs.xml")
        adb("reverse", "--remove", "tcp:8765")


if __name__ == "__main__":
    main()
