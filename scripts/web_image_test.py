"""Run Xiaomi's real Web image-event producer inside a nonce-scoped debug test."""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from fixture_provider import fixture_png
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--records", default=str(ROOT / "evidence/round4-requests.jsonl"))
    args = parser.parse_args()
    prefix = ["adb", "-s", args.serial]

    def adb(*command):
        return subprocess.run(prefix + list(command), check=True, capture_output=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    directory = ROOT / "evidence"
    records = Path(args.records)
    nonce = uuid.uuid4().hex
    app = "io.github.xiaoaiswitch"
    backup = root(f"cat /data/user/0/{app}/shared_prefs/switch.xml")
    config = {"enabled": True, "fixtureNonce": nonce, "revision": int(time.time()*1000), "autoRoute": False,
              "chat": {"enabled": True, "endpoint": "http://127.0.0.1:8765/v1", "model": "FIXTURE_CHAT", "token": "FIXTURE_TOKEN"},
              "image": {"enabled": True, "endpoint": "http://127.0.0.1:8765/v1", "model": "FIXTURE_IMAGE", "token": "FIXTURE_TOKEN", "systemPrompt": "WEB_IMAGE_SYSTEM"}}
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps(config)
    path = directory / "web-image-prefs.xml"
    ET.ElementTree(prefs).write(path, encoding="utf8", xml_declaration=True)
    original = directory / "web-image-original.png"
    original.write_bytes(fixture_png())
    try:
        adb("shell", "am", "force-stop", app)
        adb("push", str(path), "/data/local/tmp/xasw-web-prefs.xml")
        adb("shell", "run-as", app, "cp", "/data/local/tmp/xasw-web-prefs.xml", "shared_prefs/switch.xml")
        adb("push", str(original), "/sdcard/Download/xasw-native-edit-input.png")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        root("am start -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        seed_count = len(records.read_text(encoding="utf8").splitlines()) if records.exists() else 0
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService -a com.miui.voiceassist.query "
             "--es assist_query WEB_SEED --es voice_assist_start_from_key xiaoai_switch_fixture --ez use_offline_nlp false --ez isCloseMic true")
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            rows = records.read_text(encoding="utf8").splitlines() if records.exists() else []
            if len(rows) > seed_count:
                break
            time.sleep(.2)
        assert len(rows) > seed_count, "Seed request did not initialize native engine"
        time.sleep(.5)
        before = len(records.read_text(encoding="utf8").splitlines())
        query = "WEB_EDIT_" + nonce[:8]
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService -a io.github.xiaoaiswitch.FIXTURE "
             "--es mode web_image --es nonce " + nonce + " --es path /sdcard/Download/xasw-native-edit-input.png --es query " + query)
        deadline = time.monotonic() + 20
        observed = []
        while time.monotonic() < deadline:
            observed = [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()[before:]
                        if json.loads(line)["path"] == "/v1/images/edits"]
            if observed:
                break
            time.sleep(.2)
        assert observed and observed[0]["path"] == "/v1/images/edits", "Real Web event did not reach edit API"
        request = observed[0]
        assert request["body"]["model"] == "FIXTURE_IMAGE" and request["fixture_token_matches"]
        assert request["body"]["prompt"] == "WEB_IMAGE_SYSTEM\n\n" + query
        assert request["image_inputs"][0]["sha256"] == hashlib.sha256(original.read_bytes()).hexdigest()
        time.sleep(1)
        adb("shell", "uiautomator dump /sdcard/xasw-web.xml")
        xml = adb("shell", "cat /sdcard/xasw-web.xml")
        directory.joinpath("web-image-e2e.xml").write_bytes(xml)
        directory.joinpath("web-image-e2e.png").write_bytes(adb("exec-out", "screencap", "-p"))
        assert any(node.get("text") == query for node in ET.fromstring(xml).iter("node")), "Web response missing from native Gallery"
        pixels = Image.open(directory / "web-image-e2e.png").convert("RGB").get_flattened_data()
        assert sum(1 for pixel in pixels if all(abs(a-b)<=3 for a,b in zip(pixel,(70,90,220)))) > 500
        directory.joinpath("web-image-e2e-request.json").write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding="utf8")
        print(json.dumps({"passed": True, "real_producer": "vf0.a.sendWebImageGenEvent", "path": request["path"]}))
    finally:
        adb("shell", "am", "force-stop", app)
        path.write_bytes(backup)
        adb("push", str(path), "/data/local/tmp/xasw-web-prefs.xml")
        adb("shell", "run-as", app, "cp", "/data/local/tmp/xasw-web-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "--remove", "tcp:8765")


if __name__ == "__main__":
    main()
