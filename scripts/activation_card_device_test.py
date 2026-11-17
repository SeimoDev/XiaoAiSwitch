"""Preview both status visuals, confirm real activation, and optionally commit legacy migration."""
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
    parser.add_argument("--persist-migration", action="store_true")
    args = parser.parse_args()
    prefix = ["adb", "-s", args.serial]
    out = ROOT / "evidence"

    def adb(*command):
        return subprocess.run(prefix + list(command), capture_output=True, check=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def prefs():
        return root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml")

    def write(data):
        adb("shell", "am", "force-stop", APP)
        path = out / "activation-preview-prefs.xml"
        path.write_bytes(data)
        adb("push", str(path), "/data/local/tmp/xasw-activation.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xasw-activation.xml", "shared_prefs/switch.xml")
        path.unlink()

    def start():
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "start", "-W", "-n", APP + "/.SettingsActivity")

    def tree(name=None):
        adb("shell", "input", "keyevent", "224")
        adb("shell", "uiautomator dump /sdcard/xasw-activation.xml")
        xml = adb("shell", "cat /sdcard/xasw-activation.xml")
        if name:
            out.joinpath(name + ".xml").write_bytes(xml)
            out.joinpath(name + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        return ET.fromstring(xml)

    def tap(tag):
        for _ in range(8):
            n = next((n for n in tree().iter("node") if n.get("resource-id") == tag), None)
            if n is not None:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
                if y2-y1 >= 80:
                    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                    return
            w, h = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm size").decode())[-1])
            adb("shell", "input", "swipe", str(w-12), str(h*3//4), str(w-12), str(h//3), "300")
        raise AssertionError("Missing UI control: " + tag)

    backup = prefs()
    original = json.loads(next(n.text for n in ET.fromstring(backup) if n.get("name") == "config"))
    night = adb("shell", "cmd", "uimode", "night").decode().strip().split()[-1]
    try:
        # Only the local acknowledgement cache is a fixture; LSPosed itself remains enabled.
        preview = ET.fromstring(backup)
        for n in list(preview):
            if n.get("name") == "activeVersion":
                preview.remove(n)
        ET.SubElement(preview, "int", name="activeVersion", value="0")
        write(ET.tostring(preview, encoding="utf8", xml_declaration=True))
        try:
            for mode in ["no", "yes"]:
                adb("shell", "cmd", "uimode", "night", mode)
                start()
                name = "activation-inactive-" + ("dark" if mode == "yes" else "light")
                t = tree(name)
                assert "未激活" in next(n.get("text") for n in t.iter("node") if n.get("resource-id") == "activation_status")
                card = next(n for n in t.iter("node") if n.get("resource-id") == "activation_card")
                x, y, _, _ = map(int, re.findall(r"\d+", card.get("bounds")))
                p = Image.open(out / (name + ".png")).convert("RGB").getpixel((x+100, y+30))
                assert p[0] > p[1] and p[0] > p[2], p
        finally:
            write(backup)

        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        root("am start -W -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        for mode in ["no", "yes"]:
            adb("shell", "cmd", "uimode", "night", mode)
            start()
            name = "activation-active-" + ("dark" if mode == "yes" else "light")
            t = tree(name)
            assert next(n.get("text") for n in t.iter("node") if n.get("resource-id") == "activation_status") == "已激活"
        adb("shell", "cmd", "uimode", "night", night)
        start()
        if args.persist_migration:
            tap("menu_general")
            tap("save_settings")
            time.sleep(.4)
            out.joinpath("save-feedback-final.png").write_bytes(adb("exec-out", "screencap", "-p"))
            current = json.loads(next(n.text for n in ET.fromstring(prefs()) if n.get("name") == "config"))
            assert current["enabled"] == original["enabled"]
            assert current.get("providers")
            for route in ["chat", "voice", "image", "control"]:
                if route not in original:
                    continue
                assert current[route]["model"] == original[route].get("model", "").strip()
                assert current[route]["systemPrompt"] == original[route].get("systemPrompt", "")
                assert not any(key in current[route] for key in ["provider", "endpoint", "token"])
                if current[route].get("enabled"):
                    assert current[route]["providerId"] in current["providers"]
            tap("settings_back")
        tree("home-final")
        print(json.dumps({"passed": True, "real_activation": True, "inactive_visual_fixture": True,
                          "light_dark": True, "migration_committed": args.persist_migration}, ensure_ascii=False))
    finally:
        adb("shell", "cmd", "uimode", "night", night)
        adb("shell", "rm", "/data/local/tmp/xasw-activation.xml")


if __name__ == "__main__":
    main()
