"""Real phone-owned subscription generation/edit/delegation; no fixture backend or token export."""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
from fixture_provider import fixture_png
from fixture_config import with_registry

ROOT = Path(__file__).resolve().parents[1]
APP = "io.github.xiaoaiswitch"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["generate", "edit", "auto"], default="generate")
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--registry", action="store_true")
    args = parser.parse_args()
    prefix = ["adb", "-s", args.serial]
    directory = ROOT / "evidence"
    name = "codex-native-image-" + args.mode

    def adb(*command):
        return subprocess.run(prefix + list(command), capture_output=True, check=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def status():
        prefs = ET.fromstring(root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml"))
        return next((n.text or "" for n in prefs if n.get("name") == "status"), "")

    def tree(suffix=None):
        adb("shell", "input", "keyevent", "224")
        adb("shell", "uiautomator dump /sdcard/xa-codex-image.xml")
        xml = adb("shell", "cat /sdcard/xa-codex-image.xml")
        if suffix is not None:
            directory.joinpath(name + suffix + ".xml").write_bytes(xml)
            directory.joinpath(name + suffix + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        return ET.fromstring(xml)

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def wait_node(suffix):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            found = next((n for n in tree().iter("node") if n.get("resource-id", "").endswith(suffix)), None)
            if found is not None:
                return found
            time.sleep(.3)
        raise AssertionError("Native view missing: " + suffix)

    backup = root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml")
    marker = "CODEX_IMAGE_" + args.mode.upper() + "_" + str(time.time_ns())[-6:]
    query = marker + " Draw one cute red cat on a pure white background, flat illustration, no text."
    if args.mode == "edit":
        query = marker + " Turn the uploaded checkerboard into a blue cat on white, no text."
    config = {"enabled": True, "revision": int(time.time()*1000), "defaultRoute": "image", "autoRoute": False,
              "image": {"enabled": True, "provider": "codex_subscription", "model": "gpt-image-2",
                        "systemPrompt": "Create the requested illustration. Ignore the test identifier and do not put letters or labels in the image."}}
    if args.mode == "auto":
        config.update(defaultRoute="chat", autoRoute=True, chat={"enabled": True, "provider": "codex_subscription", "model": "gpt-6.1-sol",
            "systemPrompt": "When asked to draw, call generate_image once with image=none. After success, reply only CODEX_NATIVE_IMAGE_AUTO_OK."})
        query = "帮我画一只绿色的小猫，白色背景，不要文字。"
    if args.registry:
        with_registry(config)
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps(config, ensure_ascii=False)
    temporary = directory / "codex-native-image-prefs.xml"
    ET.ElementTree(prefs).write(temporary, encoding="utf8", xml_declaration=True)
    source = directory / "codex-native-edit-original.png"
    source.write_bytes(fixture_png())
    try:
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("shell", "am", "force-stop", APP)
        adb("push", str(temporary), "/data/local/tmp/xa-codex-image-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xa-codex-image-prefs.xml", "shared_prefs/switch.xml")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        if args.mode == "edit":
            adb("push", str(source), "/sdcard/Download/xasw-codex-edit-input.png")
            root("am start -a android.intent.action.VIEW -d 'voiceassist://aiimg/img2img?from=XIAOAI_SWITCH_TEST' "
                 "-p com.miui.voiceassist --es image_path /sdcard/Download/xasw-codex-edit-input.png")
            field = wait_node(":id/et_min_input")
            tap(field)
            field = wait_node(":id/et_min_input")
            if field.get("focused") != "true":
                tap(field)
            adb("shell", "input", "text", query.replace(" ", "%s"))
            tap(wait_node(":id/btn_text_send"))
        else:
            root("am start -W -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
            root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService "
                 "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
                 "--ez use_offline_nlp false --ez isCloseMic true --es assist_query " + shlex.quote(query))
        observed = []
        deadline = time.monotonic() + 370
        while time.monotonic() < deadline:
            current = status()
            if not observed or observed[-1] != current:
                observed.append(current)
                print(current, flush=True)
            if "失败" in current or "绘图响应成功" in current or (args.mode == "auto" and "自定义请求成功：chat" in current):
                break
            time.sleep(.5)
        time.sleep(1)
        current = tree("")
        assert "失败" not in status(), {"status": status(), "visible": [n.get("text", "") for n in current.iter("node")][-12:]}
        picture = next((n for n in current.iter("node") if n.get("resource-id", "").endswith(":id/imageview")), None)
        assert picture is not None, {"status": status(), "error": "Native generated image is missing"}
        rect = tuple(map(int, re.findall(r"\d+", picture.get("bounds"))))
        pixels = Image.open(directory / (name + ".png")).convert("RGB").crop(rect)
        assert len(set(pixels.get_flattened_data())) > 100, "Image result is still blank/loading"
        if args.mode == "auto":
            assert any(n.get("text") == "CODEX_NATIVE_IMAGE_AUTO_OK" for n in current.iter("node"))
        if args.mode == "edit":
            assert any("图生图 / gpt-image-2" in value for value in observed), "Original image did not reach the edit route"
        if args.preview:
            tap(picture)
            preview = tree("-preview")
            assert any(n.get("content-desc") == "自定义绘图预览" for n in preview.iter("node")), "Preview did not open"
            adb("shell", "input", "keyevent", "4")
        result = {"passed": True, "provider": "codex_subscription", "model": "gpt-image-2", "mode": args.mode,
                  "phone_direct": True, "api_key_used": False, "observed": observed,
                  "original_sha256": hashlib.sha256(source.read_bytes()).hexdigest() if args.mode == "edit" else None}
        directory.joinpath(name + "-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf8")
        print(json.dumps(result, ensure_ascii=False))
    finally:
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("shell", "am", "force-stop", APP)
        temporary.write_bytes(backup)
        adb("push", str(temporary), "/data/local/tmp/xa-codex-image-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xa-codex-image-prefs.xml", "shared_prefs/switch.xml")
        adb("shell", "rm", "/data/local/tmp/xa-codex-image-prefs.xml")
        if args.mode == "edit":
            adb("shell", "rm", "/sdcard/Download/xasw-codex-edit-input.png")
        temporary.unlink()


if __name__ == "__main__":
    main()
