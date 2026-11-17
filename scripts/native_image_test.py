"""Exercise Xiaomi's production image editor and real multipart backend, not a copied UI."""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from fixture_provider import fixture_png
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--create", action="store_true")
    parser.add_argument("--records", default=str(ROOT / "evidence/round4-requests.jsonl"))
    args = parser.parse_args()
    directory = ROOT / "evidence"
    directory.mkdir(exist_ok=True)
    prefix = ["adb", "-s", args.serial]

    def adb(*command):
        return subprocess.run(prefix + list(command), check=True, capture_output=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def tree():
        adb("shell", "uiautomator dump /sdcard/xasw-native-image.xml")
        return ET.fromstring(adb("shell", "cat /sdcard/xasw-native-image.xml"))

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def wait_node(suffix):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            try:
                current = tree()
                node = next((node for node in current.iter("node") if node.get("resource-id", "").endswith(suffix)), None)
                if node is not None:
                    return node
            except (subprocess.CalledProcessError, ET.ParseError):
                pass
            time.sleep(.3)
        raise AssertionError("Native node missing: " + suffix)

    app = "io.github.xiaoaiswitch"
    before_config = root(f"cat /data/user/0/{app}/shared_prefs/switch.xml")
    config = {"enabled": True, "revision": int(time.time()*1000), "defaultRoute": "chat", "image": {
        "enabled": True, "endpoint": "http://127.0.0.1:8765/v1", "model": "FIXTURE_IMAGE", "token": "FIXTURE_TOKEN", "systemPrompt": "NATIVE_IMAGE_SYSTEM"}}
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps(config)
    path = directory / "native-image-prefs.xml"
    ET.ElementTree(prefs).write(path, encoding="utf-8", xml_declaration=True)
    source = directory / "native-image-original.png"
    source.write_bytes(fixture_png())
    records = Path(args.records)
    before = len(records.read_text(encoding="utf8").splitlines()) if records.exists() else 0
    query = ("NATIVE_CREATE_" if args.create else "NATIVE_EDIT_") + str(time.time_ns())[-7:]
    name = "native-create-e2e" if args.create else "native-edit-e2e"
    try:
        adb("shell", "am", "force-stop", app)
        adb("push", str(path), "/data/local/tmp/xasw-native-image-prefs.xml")
        adb("shell", "run-as", app, "cp", "/data/local/tmp/xasw-native-image-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("push", str(source), "/sdcard/Download/xasw-native-edit-input.png")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        command = "am start -a android.intent.action.VIEW -d 'voiceassist://aiimg/img2img?from=XIAOAI_SWITCH_TEST' -p com.miui.voiceassist"
        if not args.create:
            command += " --es image_path /sdcard/Download/xasw-native-edit-input.png"
        root(command)
        field = wait_node(":id/et_min_input")
        tap(field)
        time.sleep(.4)
        # Wait until the real editor's input focus/IME transition has completed.
        field = wait_node(":id/et_min_input")
        if field.get("focused") != "true":
            tap(field)
            time.sleep(.5)
        adb("shell", "input", "text", query)
        send = wait_node(":id/btn_text_send")
        tap(send)
        deadline = time.monotonic() + 25
        observed = []
        while time.monotonic() < deadline:
            observed = [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()[before:]] if records.exists() else []
            if observed:
                break
            time.sleep(.2)
        assert observed, "Native editor never reached custom provider"
        request = observed[0]
        assert request["path"] == ("/v1/images/generations" if args.create else "/v1/images/edits"), request["path"]
        assert request["body"]["model"] == "FIXTURE_IMAGE" and request["fixture_token_matches"]
        assert request["body"]["prompt"] == "NATIVE_IMAGE_SYSTEM\n\n" + query
        if not args.create:
            assert request["image_inputs"][0]["sha256"] == hashlib.sha256(source.read_bytes()).hexdigest(), request["image_inputs"]
        time.sleep(1)
        current = tree()
        directory.joinpath(name + ".xml").write_bytes(ET.tostring(current, encoding="utf8"))
        directory.joinpath(name + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        description = next((node for node in current.iter("node") if node.get("text") == query), None)
        assert description is not None, "Native Gallery description missing"
        parents = {child: parent for parent in current.iter() for child in parent}
        container = parents[description]
        while not any(node.get("resource-id", "").endswith(":id/imageview") for node in container.iter("node")):
            container = parents[container]
        picture = next(node for node in container.iter("node") if node.get("resource-id", "").endswith(":id/imageview"))
        rect = tuple(map(int, re.findall(r"\d+", picture.get("bounds"))))
        pixels = Image.open(directory / (name + ".png")).convert("RGB").crop(rect).get_flattened_data()
        color = (250, 150, 40) if args.create else (70, 90, 220)
        assert sum(1 for pixel in pixels if all(abs(a-b) <= 3 for a, b in zip(pixel, color))) > 500, "Actual response image did not render"
        directory.joinpath(name + "-request.json").write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding="utf8")
        print(json.dumps({"passed": True, "native_mode": "create" if args.create else "edit", "path": request["path"], "query": query}, ensure_ascii=False))
    finally:
        adb("shell", "am", "force-stop", app)
        path.write_bytes(before_config)
        adb("push", str(path), "/data/local/tmp/xasw-native-image-prefs.xml")
        adb("shell", "run-as", app, "cp", "/data/local/tmp/xasw-native-image-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "--remove", "tcp:8765")


if __name__ == "__main__":
    main()
