"""Verify real native mode switching, independent profiles, and visible history; restore state."""
import argparse
import json
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = "io.github.xiaoaiswitch"
STATUS_PREFIX = "THINKING_MODE_FIXTURE "


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--records", default=str(ROOT / "evidence/switch-home-regression.jsonl"))
    parser.add_argument("--route", choices=["chat", "voice", "all"], default="all")
    parser.add_argument("--surface", choices=["main", "float"], default="main")
    args = parser.parse_args()
    prefix = ["adb", "-s", args.serial]
    directory = ROOT / "evidence"
    directory.mkdir(exist_ok=True)
    records = Path(args.records)
    nonce = uuid.uuid4().hex

    def adb(*command):
        return subprocess.run(prefix + list(command), capture_output=True, check=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def status():
        # SharedPreferences can be between its truncate/write/rename steps when observed externally.
        for attempt in range(8):
            try:
                prefs = ET.fromstring(root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml"))
                return next((node.text or "" for node in prefs if node.get("name") == "status"), "")
            except ET.ParseError:
                if attempt == 7:
                    raise
                time.sleep(.05)

    def tree():
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "uiautomator dump /sdcard/xasw-thinking.xml")
        return ET.fromstring(adb("shell", "cat /sdcard/xasw-thinking.xml"))

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def mode_fixture(value=None):
        request_id = uuid.uuid4().hex
        command = ("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService "
                   "-a io.github.xiaoaiswitch.FIXTURE --es mode thinking_mode --es nonce " + nonce + " --es request " + request_id)
        if value is not None:
            command += " --ez thinking " + str(value).lower()
        root(command)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            value = status()
            if value.startswith(STATUS_PREFIX):
                result = json.loads(value[len(STATUS_PREFIX):])
                if result.get("request") == request_id:
                    return result
            time.sleep(.1)
        raise AssertionError("Native thinking fixture did not report a state")

    switches = []

    def set_mode(wanted):
        before = mode_fixture()
        method = "unchanged"
        if before["current"] != wanted:
            native = next((node for node in tree().iter("node")
                           if node.get("resource-id") == "com.miui.voiceassist:id/btn_deep_think"
                           and node.get("clickable") == "true" and node.get("enabled") == "true"), None)
            if native is not None:
                tap(native)
                time.sleep(.4)
                after = mode_fixture()
                method = "native_ui"
            else:
                after = before
            if after["current"] != wanted:
                after = mode_fixture(wanted)
                method = "native_helper"
        else:
            after = before
        assert after["current"] == wanted, after
        if after["effective"] != wanted:
            time.sleep(2)
            after = mode_fixture()
        assert after["effective"] == wanted, {"state": after, "reason": "A native one-shot override is still active"}
        switches.append({"requested": wanted, "method": method, **after})
        print(json.dumps({"mode": "thinking" if wanted else "default", "method": method}, ensure_ascii=False), flush=True)
        time.sleep(.3)

    def rows():
        return [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()] if records.exists() else []

    def request(route, query):
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        before = len(rows())
        text = "/voice " + query if route == "voice" else "/chat " + query
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService "
             "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
             "--ez use_offline_nlp false --ez isCloseMic true --es assist_query " + shlex.quote(text))
        deadline = time.monotonic() + 25
        found = None
        while time.monotonic() < deadline:
            found = next((row for row in rows()[before:] if row.get("body", {}).get("messages", [{}])[-1].get("content") == query), None)
            if found is not None:
                break
            time.sleep(.15)
        assert found is not None, "No fixture request for " + query
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            current = status()
            assert "请求失败" not in current, current
            if route == "voice" and "原厂 TTS 播放结束（中断=false）" in current:
                return found
            if route == "chat" and "自定义请求成功：chat" in current:
                return found
            time.sleep(.15)
        raise AssertionError("Reply did not finish: " + status())

    def profile(route, thinking):
        mode = "thinking" if thinking else "default"
        model = "FIXTURE_THINKING" if thinking else "FIXTURE_DEFAULT"
        return {"enabled": True, "provider": "openai_compatible", "endpoint": "http://127.0.0.1:8765/planner/" + mode + "/v1",
                "token": model + "_TOKEN", "model": model, "systemPrompt": route.upper() + "_" + mode.upper() + "_SYSTEM"}

    backup = root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml")
    original_config = json.loads(next(node.text for node in ET.fromstring(backup) if node.get("name") == "config"))
    config = {"enabled": True, "autoRoute": False, "revision": int(time.time()*1000), "fixtureNonce": nonce, "defaultRoute": "chat"}
    for route in ["chat", "voice"]:
        config[route] = {**profile(route, False), "thinking": profile(route, True)}
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps(config)
    temporary = directory / "thinking-mode-prefs.xml"
    ET.ElementTree(prefs).write(temporary, encoding="utf8", xml_declaration=True)
    original = None
    checks = []
    try:
        # Keep the target process alive so its pre-test native mode can be restored.
        adb("shell", "am", "force-stop", APP)
        adb("push", str(temporary), "/data/local/tmp/xasw-thinking-mode.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xasw-thinking-mode.xml", "shared_prefs/switch.xml")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        activity = "com.xiaomi.voiceassistant.TabHostMainActivity" if args.surface == "main" else "com.xiaomi.voiceassistant.mainui.home.FloatActivity"
        root("am start -W -n com.miui.voiceassist/" + activity)
        original = mode_fixture()["current"]
        for route in ["chat", "voice"] if args.route == "all" else [args.route]:
            previous = []
            for index, thinking in enumerate([False, True, False]):
                set_mode(thinking)
                query = "MODE_" + route.upper() + "_" + ["DEFAULT_A", "THINKING_B", "DEFAULT_C"][index] + "_" + nonce[:6]
                actual = request(route, query)
                expected = profile(route, thinking)
                body = actual["body"]
                assert actual["path"] == "/planner/" + ("thinking" if thinking else "default") + "/v1/chat/completions", actual["path"]
                assert actual["fixture_token_matches"] and body["model"] == expected["model"]
                assert body["messages"][0] == {"role": "system", "content": expected["systemPrompt"]}
                for past in previous:
                    assert any(message.get("role") == "user" and message.get("content") == past for message in body["messages"]), "Visible history lost across mode switch"
                assert any(node.get("text") == "XASWITCH_FIXTURE_OK" for node in tree().iter("node")), "Native answer missing"
                previous.append(query)
                checks.append({"route": route, "thinking": thinking, "path": actual["path"], "model": body["model"],
                               "systemPrompt": body["messages"][0]["content"], "messages": len(body["messages"])})
                print(json.dumps(checks[-1], ensure_ascii=False), flush=True)
            directory.joinpath("thinking-mode-" + route + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        result = {"passed": True, "requests": checks, "native_switches": switches, "original_mode": original, "surface": args.surface,
                  "voice_path": "Text query through voice LLM/TTS; no synthetic ASR result and no microphone test"}
    finally:
        try:
            if original is not None:
                restored = mode_fixture(original)
                assert restored["current"] == original, "Original native thinking mode was not restored"
        finally:
            adb("shell", "am", "force-stop", APP)
            temporary.write_bytes(backup)
            adb("push", str(temporary), "/data/local/tmp/xasw-thinking-mode.xml")
            adb("shell", "run-as", APP, "cp", "/data/local/tmp/xasw-thinking-mode.xml", "shared_prefs/switch.xml")
            restored = ET.fromstring(root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml"))
            configuration_restored = json.loads(next(node.text for node in restored if node.get("name") == "config")) == original_config
            adb("reverse", "--remove", "tcp:8765")
            adb("shell", "rm", "/data/local/tmp/xasw-thinking-mode.xml")
            temporary.unlink()
            assert configuration_restored, "Original module configuration was not restored"
    result.update(native_mode_restored=True, configuration_restored=True)
    directory.joinpath("thinking-mode-results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf8")
    print(json.dumps({"passed": True, "requests": len(checks), "visible_history_preserved": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()
