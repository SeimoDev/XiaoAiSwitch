"""Device integration gates: history, reset, configuration/UI changes, supersession, failure, disable."""
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--records", default=str(ROOT / "evidence/round4-requests.jsonl"))
    args = parser.parse_args()
    prefix = ["adb", "-s", args.serial]
    directory = ROOT / "evidence"
    records = Path(args.records)
    app = "io.github.xiaoaiswitch"
    results = {}

    def adb(*command):
        return subprocess.run(prefix + list(command), check=True, capture_output=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def rows():
        return [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()] if records.exists() else []

    def save(config):
        prefs = ET.Element("map")
        ET.SubElement(prefs, "string", name="config").text = json.dumps(config)
        path = directory / "lifecycle-prefs.xml"
        ET.ElementTree(prefs).write(path, encoding="utf8", xml_declaration=True)
        adb("shell", "am", "force-stop", app)
        adb("push", str(path), "/data/local/tmp/xasw-lifecycle.xml")
        adb("shell", "run-as", app, "cp", "/data/local/tmp/xasw-lifecycle.xml", "shared_prefs/switch.xml")

    def send(text):
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService -a com.miui.voiceassist.query "
             "--es voice_assist_start_from_key xiaoai_switch_fixture --ez use_offline_nlp false --ez isCloseMic true --es assist_query " + shlex.quote(text))

    def request(text, done=True):
        before = len(rows())
        send(text)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            found = rows()[before:]
            if found:
                if done:
                    time.sleep(.6)
                return found[0]
            time.sleep(.1)
        raise AssertionError("Request not observed: " + text)

    def tree(name):
        adb("shell", "uiautomator dump /sdcard/xasw-lifecycle.xml")
        xml = adb("shell", "cat /sdcard/xasw-lifecycle.xml")
        directory.joinpath(name + ".xml").write_bytes(xml)
        return ET.fromstring(xml)

    def tap(node):
        x1,y1,x2,y2 = map(int,re.findall(r"\d+",node.get("bounds")))
        adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))

    backup = root(f"cat /data/user/0/{app}/shared_prefs/switch.xml")
    nonce = uuid.uuid4().hex
    config = {"enabled": True, "revision": int(time.time()*1000), "autoRoute": False, "fixtureNonce": nonce,
              "chat": {"enabled": True,"endpoint":"http://127.0.0.1:8765/v1","token":"FIXTURE_TOKEN","model":"FIXTURE_CHAT","systemPrompt":"SYSTEM_A"}}
    try:
        save(config)
        adb("reverse","tcp:8765","tcp:8765")
        adb("shell","input","keyevent","224")
        adb("shell","wm","dismiss-keyguard")
        adb("shell","am","force-stop","com.miui.voiceassist")
        root("am start -W -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        request("HISTORY_A")
        second = request("HISTORY_B")
        assert any(message.get("content")=="HISTORY_A" for message in second["body"]["messages"]), second["body"]
        results["history"] = True
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService -a io.github.xiaoaiswitch.FIXTURE --es mode clear --es nonce " + nonce)
        time.sleep(.3)
        cleared = request("HISTORY_C")
        assert not any(message.get("content")=="HISTORY_A" for message in cleared["body"]["messages"])
        results["clear_session"] = True
        config["revision"] += 1
        config["chat"].update(endpoint="http://127.0.0.1:8765/changed/v1", model="FIXTURE_CHANGED", systemPrompt="CHANGED_SYSTEM")
        save(config)
        changed = request("CONFIG_CHANGED")
        assert changed["path"]=="/changed/v1/chat/completions" and changed["body"]["model"]=="FIXTURE_CHANGED"
        assert changed["body"]["messages"][0]["content"]=="CHANGED_SYSTEM"
        assert len(changed["body"]["messages"])==2
        results["configuration_revision"] = True
        adb("shell","am","force-stop","com.miui.voiceassist")
        adb("shell","am","start","-W","-n",app+"/.SettingsActivity")
        current = tree("settings-lifecycle")
        tap(next(node for node in current.iter("node") if node.get("resource-id")=="menu_chat"))
        current = tree("settings-lifecycle-channel")
        for attempt in range(8):
            field = next((node for node in current.iter("node") if node.get("resource-id")=="prompt_field"), None)
            if field is not None:
                break
            adb("shell","input","swipe","1588","1900","1588","850","300")
            current = tree("settings-lifecycle")
        assert field is not None, "System Prompt field is not visible"
        tap(field)
        adb("shell","input","keycombination","113","29")
        adb("shell","input","text","UI_SYSTEM_PROMPT")
        adb("shell","input","keyevent","4")
        current = tree("settings-lifecycle-edited")
        tap(next(node for node in current.iter("node") if node.get("resource-id")=="save_settings"))
        ui = request("UI_CONFIG_TEST")
        assert ui["body"]["messages"][0]["content"]=="UI_SYSTEM_PROMPT", ui["body"]
        results["settings_ui_save"] = True
        request("DELAY_RESULT_SUPERSEDE_FIRST", done=False)
        request("SUPERSEDE_SECOND")
        time.sleep(3.5)
        current = tree("lifecycle-superseded")
        texts = [node.get("text","") for node in current.iter("node")]
        assert "FRESH_SECOND_REPLY" in texts and "STALE_FIRST_REPLY" not in texts
        results["superseded_request"] = True
        config["revision"] += 2
        config["chat"]["endpoint"]="http://127.0.0.1:8765/failure/v1"
        save(config)
        request("FAILURE_TEST")
        current=tree("lifecycle-failure")
        assert any("HTTP 401" in node.get("text","") for node in current.iter("node"))
        results["http_error_ui"] = True
        config["revision"]+=1
        config["chat"]["endpoint"]="http://127.0.0.1:8765/v1"
        save(config)
        request("RECOVER_TEST")
        assert any(node.get("text")=="XASWITCH_FIXTURE_OK" for node in tree("lifecycle-recovered").iter("node"))
        results["recovery"] = True
        config["enabled"]=False
        config["revision"]+=1
        save(config)
        before=len(rows())
        send("OFF_PROBE")
        time.sleep(2)
        assert len(rows())==before, "Disabled module called custom service"
        results["disabled_passthrough"] = True
        directory.joinpath("lifecycle-results.json").write_text(json.dumps(results,indent=2),encoding="utf8")
        print(json.dumps({"passed":True,"gates":results}))
    finally:
        directory.joinpath("lifecycle-partial-results.json").write_text(json.dumps(results,indent=2),encoding="utf8")
        adb("shell","am","force-stop",app)
        path=directory/"lifecycle-restore.xml"
        path.write_bytes(backup)
        adb("push",str(path),"/data/local/tmp/xasw-lifecycle.xml")
        adb("shell","run-as",app,"cp","/data/local/tmp/xasw-lifecycle.xml","shared_prefs/switch.xml")
        adb("reverse","--remove","tcp:8765")


if __name__=="__main__":
    main()
