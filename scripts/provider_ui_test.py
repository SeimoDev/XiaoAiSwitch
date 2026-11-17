"""Real Miuix provider CRUD, model selection, thinking drafts and immediate switch."""
import argparse
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
APP = "io.github.xiaoaiswitch"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    directory = ROOT / "evidence"
    prefix = ["adb", "-s", args.serial]

    def adb(*command):
        return subprocess.run(prefix + list(command), capture_output=True, check=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def size():
        return tuple(map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm size").decode())[-1]))

    def tree(name=None):
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "uiautomator dump /sdcard/xasw-provider-ui.xml")
        xml = adb("shell", "cat /sdcard/xasw-provider-ui.xml")
        if name:
            directory.joinpath("providers-" + name + ".xml").write_bytes(xml)
            directory.joinpath("providers-" + name + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        return ET.fromstring(xml)

    def top():
        w, h = size()
        for _ in range(5):
            adb("shell", "input", "swipe", str(w-12), str(h//3), str(w-12), str(h*3//4), "150")

    def node(tag, scroll=True, min_height=0):
        for attempt in range(10 if scroll else 1):
            for n in tree().iter("node"):
                if n.get("resource-id") != tag:
                    continue
                rect = list(map(int, re.findall(r"\d+", n.get("bounds"))))
                if rect[3]-rect[1] >= min_height:
                    return n
            if not scroll:
                break
            if attempt == 0:
                top()
                continue
            w, h = size()
            adb("shell", "input", "swipe", str(w-12), str(h*3//4), str(w-12), str(h//3), "300")
        raise AssertionError("UI tag not visible: " + tag)

    def tap(target):
        n = node(target, min_height=80) if isinstance(target, str) else target
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def choose(text):
        tap(next(n for n in tree().iter("node") if n.get("text") == text))

    def edit(tag, text):
        tap(tag)
        adb("shell", "input", "keycombination", "113", "29")
        if text:
            adb("shell", "input", "text", text)
        else:
            adb("shell", "input", "keyevent", "67")
        adb("shell", "input", "keyevent", "4")

    def config():
        for _ in range(10):
            try:
                xml = ET.fromstring(root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml"))
                return json.loads(next(n.text for n in xml if n.get("name") == "config"))
            except ET.ParseError:
                time.sleep(.1)
        raise AssertionError("Config XML was not readable")

    def provider(name):
        return next((key, value) for key, value in config()["providers"].items() if value["name"] == name)

    def open_provider(name):
        tap("provider_" + provider(name)[0])

    def back():
        tap("settings_back")

    def pick_provider(name):
        tap("provider_selector")
        choose(name)

    backup = root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml")
    original_size = re.search(r"Override size: (\d+x\d+)", adb("shell", "wm size").decode())
    night = adb("shell", "cmd", "uimode", "night").decode().strip().split()[-1]
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps({
        "enabled": False, "autoRoute": True, "controlSteps": 12, "defaultRoute": "chat", "fixtureNonce": "PROVIDERS_UI_TEST",
        "chat": {"enabled": True, "endpoint": "https://HOST/v1", "token": "UI_TOKEN", "model": "UI_CHAT", "systemPrompt": "UI_SYSTEM"},
        "voice": {"endpoint": "https://VOICE/v1", "model": "UI_VOICE"},
    })
    temp = directory / "provider-ui-prefs.xml"
    ET.ElementTree(prefs).write(temp, encoding="utf8", xml_declaration=True)
    try:
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("shell", "am", "force-stop", APP)
        adb("push", str(temp), "/data/local/tmp/xasw-provider-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xasw-provider-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("shell", "cmd", "uimode", "night", "no")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "start", "-W", "-n", APP + "/.SettingsActivity")
        node("home_screen", scroll=False)
        root("am start -W -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("shell", "am", "start", "-W", "-n", APP + "/.SettingsActivity")
        assert "已激活" in node("activation_status", scroll=False).get("text", "")
        node("master_switch", scroll=False)
        tree("home-light")

        tap("menu_providers")
        tap("add_provider")
        edit("provider_name", "UI_CODEX")
        tap("provider_kind")
        choose("Codex 订阅")
        tap("provider_account")
        assert "已连接" in node("account_status", scroll=False).get("text", "")
        tap("logout_account")
        tap("cancel_logout")
        tap("account_back")
        tap("save_provider")
        node("save_feedback", scroll=False)
        assert provider("UI_CODEX")[1]["provider"] == "codex_subscription"
        print("Central Codex provider and preserved login: passed", flush=True)

        tap("add_provider")
        edit("provider_name", "UI_THINK")
        edit("provider_endpoint", "http://127.0.0.1:8765/ui/v1")
        edit("provider_token", "UI_THINK_TOKEN")
        tap("save_provider")
        assert provider("UI_THINK")[1]["token"] == "UI_THINK_TOKEN"
        open_provider("API 1")
        assert node("delete_provider").get("enabled") == "false"
        edit("provider_name", "UI_DEFAULT")
        edit("provider_token", "UI_DEFAULT_TOKEN")
        tap("save_provider")
        back()

        tap("menu_chat")
        assert not any(n.get("resource-id") == "endpoint_field" for n in tree().iter("node"))
        pick_provider("UI_CODEX")
        tap("select_model")
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if any(n.get("text") == "gpt-6.1-sol" for n in tree().iter("node")):
                break
        choose("gpt-6.1-sol")
        pick_provider("UI_DEFAULT")
        edit("model_field", "")
        assert node("model_field").get("text", "") == ""
        tap("save_settings")
        node("save_feedback", scroll=False)
        assert config()["chat"]["model"] == "UI_CHAT"
        edit("model_field", "MIUIX_CHAT")
        edit("prompt_field", "MIUIX_SYSTEM_PROMPT")
        tap("mode_thinking")
        tap("channel_switch")
        pick_provider("UI_THINK")
        tap("select_model")
        choose("FIXTURE_THINKING")
        edit("prompt_field", "THINK_SYSTEM_PROMPT")
        tap("mode_default")
        assert node("model_field").get("text") == "MIUIX_CHAT"
        back()
        tap("master_switch")
        assert config()["enabled"] is True and config()["chat"]["model"] == "UI_CHAT"
        tap("master_switch")
        assert config()["enabled"] is False
        print("Provider/model selection, save errors and protected drafts: passed", flush=True)

        tap("menu_voice")
        node("mode_thinking")
        edit("model_field", "MIUIX_VOICE")
        back()
        tap("menu_image")
        pick_provider("UI_CODEX")
        assert node("model_field").get("text") == "gpt-image-2"
        tap("channel_switch")
        back()
        tap("menu_chat")
        adb("shell", "wm", "size", "2560x1600")
        assert node("model_field").get("text") == "MIUIX_CHAT"
        tree("model-wide")
        back()
        tap("menu_general")
        tap("steps_plus")
        tap("default_route")
        choose("语音")
        tap("save_settings")
        node("save_feedback", scroll=False)
        current = config()
        assert current["enabled"] is False and current["controlSteps"] == 13 and current["defaultRoute"] == "voice"
        assert current["chat"]["providerId"] == provider("UI_DEFAULT")[0]
        assert current["chat"]["thinking"]["providerId"] == provider("UI_THINK")[0]
        assert current["chat"]["thinking"]["model"] == "FIXTURE_THINKING"
        assert current["chat"]["thinking"]["systemPrompt"] == "THINK_SYSTEM_PROMPT"
        assert current["voice"]["model"] == "MIUIX_VOICE" and current["image"]["providerId"] == provider("UI_CODEX")[0]
        assert current["fixtureNonce"] == "PROVIDERS_UI_TEST"
        back()
        tap("menu_providers")
        open_provider("UI_THINK")
        assert node("delete_provider").get("enabled") == "false"
        edit("provider_name", "UI_THINK_RENAMED")
        edit("provider_token", "UI_THINK_NEW")
        tap("save_provider")
        assert config()["chat"]["thinking"]["model"] == "FIXTURE_THINKING"
        assert provider("UI_THINK_RENAMED")[1]["token"] == "UI_THINK_NEW"

        tap("add_provider")
        edit("provider_name", "UI_TEMP")
        edit("provider_endpoint", "https://TEMP/v1")
        tap("save_provider")
        open_provider("UI_TEMP")
        tap("delete_provider")
        tap("confirm_delete_provider")
        assert not any(v["name"] == "UI_TEMP" for v in config()["providers"].values())
        back()
        top()
        tree("wide-light")
        adb("shell", "cmd", "uimode", "night", "yes")
        top()
        tree("wide-dark")
        light = Image.open(directory / "providers-wide-light.png").convert("RGB")
        dark = Image.open(directory / "providers-wide-dark.png").convert("RGB")
        assert sum(light.getpixel((10, light.height//2))) > 650
        assert sum(dark.getpixel((10, dark.height//2))) < 240
        adb("shell", "wm", "size", "1080x1920")
        top()
        tree("phone-dark")
        result = {"passed": True, "provider_crud": True, "provider_reference": True, "independent_thinking": True,
                  "model_discovery": ["compatible_api", "codex"], "save_feedback": True, "master_immediate": True,
                  "draft_rotation": True, "account_preserved": True, "light_dark": True}
        directory.joinpath("providers-ui-result.json").write_text(json.dumps(result, indent=2), encoding="utf8")
        print(json.dumps(result), flush=True)
    finally:
        adb("shell", "am", "force-stop", APP)
        adb("shell", "wm", "size", original_size.group(1) if original_size else "reset")
        adb("shell", "cmd", "uimode", "night", night if night in ["yes", "no", "auto", "custom"] else "no")
        temp.write_bytes(backup)
        adb("push", str(temp), "/data/local/tmp/xasw-provider-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xasw-provider-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "--remove", "tcp:8765")
        adb("shell", "rm", "/data/local/tmp/xasw-provider-prefs.xml")
        temp.unlink()


if __name__ == "__main__":
    main()
