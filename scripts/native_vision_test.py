"""Test 8.2's real ImageUnderstand producer with a synthetic checkerboard."""
import argparse
import hashlib
import json
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

from fixture_config import with_registry
from fixture_provider import fixture_png

ROOT = Path(__file__).resolve().parents[1]
APP = "io.github.xiaoaiswitch"
ASSISTANT = "com.miui.voiceassist"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--records", required=True, type=Path)
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    evidence = ROOT / "evidence"
    evidence.mkdir(exist_ok=True)

    def adb(*command):
        return subprocess.run(["adb", "-s", args.serial, *command], check=True, capture_output=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def requests():
        return [json.loads(line) for line in args.records.read_text(encoding="utf-8").splitlines()] if args.records.exists() else []

    def wait_for(model, start):
        until = time.monotonic() + 20
        while time.monotonic() < until:
            match = next((item for item in requests()[start:] if item["body"].get("model") == model), None)
            if match:
                return match
            time.sleep(0.2)
        state = ET.fromstring(adb("shell", "run-as", APP, "cat", "shared_prefs/switch.xml"))
        status = next((item.text for item in state if item.get("name") == "status"), "no module status")
        raise AssertionError(f"{model} did not reach the fixture server ({status})")

    def write_config(data):
        local_prefs.write_bytes(data)
        adb("shell", "am", "force-stop", APP)
        adb("push", str(local_prefs), remote_prefs)
        adb("shell", "run-as", APP, "cp", remote_prefs, "shared_prefs/switch.xml")

    profile = {"enabled": True, "endpoint": "http://127.0.0.1:8765/v1", "token": "FIXTURE_TOKEN"}
    config = with_registry({"enabled": True, "autoRoute": True, "defaultRoute": "chat", "fixtureNonce": nonce,
                            "revision": int(time.time() * 1000),
                            "chat": {**profile, "model": "FIXTURE_CHAT"},
                            "image": {**profile, "model": "FIXTURE_IMAGE"},
                            "vision": {**profile, "model": "FIXTURE_VISION", "systemPrompt": "VISION_FIXTURE_PROMPT"}})
    original = root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml")
    settings = ET.Element("map")
    ET.SubElement(settings, "string", name="config").text = json.dumps(config)
    local_prefs = evidence / ("xasw-vision-settings-" + nonce + ".xml")
    remote_prefs = "/data/local/tmp/xasw-vision-" + nonce + ".xml"
    remote_image = "/sdcard/Download/xasw-native-vision-" + nonce + ".png"
    local_image = evidence / "native-vision-checkerboard.png"
    local_image.write_bytes(fixture_png())

    try:
        write_config(ET.tostring(settings, encoding="utf-8", xml_declaration=True))
        adb("push", str(local_image), remote_image)
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "force-stop", ASSISTANT)
        root(f"am start -W -n {ASSISTANT}/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        time.sleep(1)
        start = len(requests())
        service = f"am startservice -n {ASSISTANT}/com.xiaomi.voiceassistant.SpeechQueryService"
        root(service + " -a com.miui.voiceassist.query --es assist_query VISION_ENGINE_SEED "
             "--es voice_assist_start_from_key xiaoai_switch_fixture --ez isCloseMic true --ez use_offline_nlp false")
        wait_for("FIXTURE_CHAT", start)
        query = "VISION_CHECKERBOARD_" + nonce[:8]
        start = len(requests())
        root(service + " -a io.github.xiaoaiswitch.FIXTURE --es mode native_vision --es nonce " + nonce + " --es query " + query)
        request = wait_for("FIXTURE_VISION", start)
        assert request["path"] == "/v1/chat/completions" and request["fixture_token_matches"]
        assert not request["body"].get("tools"), "Sight-only model should not receive unrelated tool requirements"
        assert request["body"]["messages"][0] == {"role": "system", "content": "VISION_FIXTURE_PROMPT"}
        assert request["body"]["messages"][-1]["content"][0]["text"] == query
        assert request["screens"][0]["sha256"] == hashlib.sha256(local_image.read_bytes()).hexdigest()
        time.sleep(1)
        (evidence / "native-vision-8.2.png").write_bytes(adb("exec-out", "screencap", "-p"))
        print(json.dumps({"passed": True, "event": "MultiModal.ImageUnderstand", "path": request["path"], "image_bytes_match": True}))
    finally:
        write_config(original)
        adb("reverse", "--remove", "tcp:8765")
        adb("shell", "rm", "-f", remote_image, remote_prefs)
        local_prefs.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
