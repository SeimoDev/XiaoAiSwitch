"""Exercise the installed module against the loopback fixture, restoring config."""
import argparse
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from fixture_config import with_registry

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--route", choices=["chat", "voice", "image", "control"], required=True)
    parser.add_argument("--query", required=True)
    parser.add_argument("--records", default=str(ROOT / "evidence/round2-requests.jsonl"))
    parser.add_argument("--name", required=True)
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--expect-tts", action="store_true")
    parser.add_argument("--default-route", choices=["chat", "voice", "image", "control"])
    parser.add_argument("--expected-text")
    parser.add_argument("--cancel", action="store_true")
    parser.add_argument("--gesture", action="store_true")
    parser.add_argument("--microphone", action="store_true")
    parser.add_argument("--manual-microphone", action="store_true")
    parser.add_argument("--steps", type=int)
    parser.add_argument("--delegate", choices=["image", "control"])
    parser.add_argument("--previous", action="store_true")
    parser.add_argument("--registry", action="store_true")
    args = parser.parse_args()
    if args.manual_microphone:
        args.microphone = True
    directory = ROOT / "evidence"
    directory.mkdir(exist_ok=True)
    prefix = ["adb", "-s", args.serial]

    def adb(*command):
        return subprocess.run(prefix + list(command), check=True, capture_output=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def capture(name):
        directory.joinpath(name + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        for _ in range(3):
            location = "/data/local/tmp/xasw-fixture.xml"
            if b"dumped to" not in adb("shell", "uiautomator", "dump", location):
                continue
            xml = adb("shell", "cat", location)
            directory.joinpath(name + ".xml").write_bytes(xml)
            return ET.fromstring(xml)
        raise AssertionError("UIAutomator did not get an idle window; see the saved screenshot")

    app = "io.github.xiaoaiswitch"

    def control_fixture():
        state = ET.fromstring(adb("shell", "run-as", app, "cat", "shared_prefs/control_fixture.xml"))
        return {node.get("name"): node.get("value", node.text) for node in state}

    records = Path(args.records)
    before = len(records.read_text(encoding="utf-8").splitlines()) if records.exists() else 0
    backup = root(f"if [ -f /data/user/0/{app}/shared_prefs/switch.xml ]; then cat /data/user/0/{app}/shared_prefs/switch.xml; else echo '<map />'; fi")
    config = {"enabled": True, "revision": int(time.time() * 1000), "defaultRoute": args.default_route or args.route,
              args.route: {"enabled": True, "endpoint": "http://127.0.0.1:8765/v1", "token": "FIXTURE_TOKEN",
                           "model": "FIXTURE_" + args.route.upper(), "systemPrompt": "FIXTURE_" + args.route.upper() + "_PROMPT"}}
    if args.steps is not None:
        config["controlSteps"] = args.steps
    if args.delegate:
        config["autoRoute"] = True
        config[args.route]["endpoint"] = "http://127.0.0.1:8765/planner/v1"
        config[args.route]["token"] = config[args.route]["model"] + "_TOKEN"
        config[args.delegate] = {"enabled": True, "endpoint": "http://127.0.0.1:8765/" + args.delegate + "/v1",
                                 "token": "FIXTURE_" + args.delegate.upper() + "_TOKEN", "model": "FIXTURE_" + args.delegate.upper(),
                                 "systemPrompt": "FIXTURE_" + args.delegate.upper() + "_PROMPT"}
    if args.registry:
        with_registry(config)
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps(config)
    path = directory / "current-fixture-prefs.xml"
    ET.ElementTree(prefs).write(path, encoding="utf-8", xml_declaration=True)
    try:
        adb("shell", "am", "force-stop", app)
        adb("shell", "run-as", app, "mkdir", "-p", "shared_prefs")
        adb("push", str(path), "/data/local/tmp/xa-current-prefs.xml")
        adb("shell", "run-as", app, "cp", "/data/local/tmp/xa-current-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        if args.microphone and not args.manual_microphone:
            adb("push", str(directory / "voice-fixture.wav"), "/data/local/tmp/xa-voice-fixture.wav")
            adb("shell", "run-as", app, "mkdir", "-p", "files")
            adb("shell", "run-as", app, "cp", "/data/local/tmp/xa-voice-fixture.wav", "files/voice-fixture.wav")
            adb("shell", "am", "start", "-n", app + "/.ControlFixtureActivity", "--ez", "play_audio", "true")
        if args.gesture:
            adb("shell", "am", "force-stop", "com.miui.voiceassist")
            adb("shell", "am", "start", "-W", "-n", app + "/.ControlFixtureActivity")
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                fixture = control_fixture()
                if all(key in fixture for key in ("increment", "input", "scrollArea", "displayWidth", "displayHeight")):
                    break
                time.sleep(.1)
            assert "increment" in fixture, "Fixture did not report laid-out controls"
            width, height = int(fixture["displayWidth"]), int(fixture["displayHeight"])

            def bounds(description):
                return [int(value) for value in fixture[description].split(",")]

            if not args.cancel:
                tap = bounds("increment")
                field = bounds("input")
                scroll = bounds("scrollArea")
                args.query = "CONTROL_GESTURE " + json.dumps({"tap": [(tap[0] + tap[2]) / 2 / width, (tap[1] + tap[3]) / 2 / height],
                    "input": [(field[0] + field[2]) / 2 / width, (field[1] + field[3]) / 2 / height],
                    "swipe": [(scroll[0] + scroll[2]) / 2 / width, (scroll[1] + (scroll[3] - scroll[1]) * .85) / height,
                              (scroll[0] + scroll[2]) / 2 / width, (scroll[1] + (scroll[3] - scroll[1]) * .25) / height]})
        root("am start -W -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        if args.microphone:
            root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.VoiceService "
                 "-a com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST --es voice_assist_start_from_key xiaoai_switch_fixture --ez isCloseMic false")
        if not args.microphone:
            root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService "
                 "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
                 "--ez use_offline_nlp false --ez isCloseMic true --es assist_query " + shlex.quote(args.query))
        deadline = time.monotonic() + (30 if args.microphone else 15)
        while time.monotonic() < deadline:
            observed = records.read_text(encoding="utf-8").splitlines() if records.exists() else []
            if len(observed) > before:
                break
            time.sleep(0.2)
        assert len(observed) > before, "No fixture request observed"
        request = json.loads(observed[before])
        assert request["fixture_token_matches"], "Authorization did not reach fixture"
        assert request["body"]["model"] == config[args.route]["model"]
        if args.delegate:
            assert request["path"] == "/planner/v1/chat/completions"
            assert request["body"]["messages"][0]["content"] == config[args.route]["systemPrompt"]
            assert request["body"]["tools"], "Planner did not advertise delegation tools"
            if args.microphone:
                recognized = request["body"]["messages"][-1]["content"]
                assert "画" in recognized and "猫" in recognized, "Expected microphone drawing request was not recognized"
                directory.joinpath(args.name + "-recognized.txt").write_text(recognized,encoding="utf8")
            deadline = time.monotonic() + 35
            while time.monotonic() < deadline:
                observed = [json.loads(line) for line in records.read_text(encoding="utf-8").splitlines()[before:]]
                planners = [row for row in observed if row["path"].startswith("/planner/")]
                if len(planners) >= 2:
                    break
                time.sleep(.2)
            assert len(planners) >= 2, "Delegation did not return to the planner"
            workers = [row for row in observed if row["path"].startswith("/" + args.delegate + "/")]
            assert workers and all(row["body"]["model"] == config[args.delegate]["model"] and row["fixture_token_matches"] for row in workers)
            if args.delegate == "image":
                assert workers[0]["body"]["prompt"].startswith(config["image"]["systemPrompt"] + "\n\n")
            if args.delegate == "control":
                assert workers[0]["body"]["messages"][0]["content"] == config["control"]["systemPrompt"]
            time.sleep(1)
            tree = capture(args.name)
            assert any(node.get("text") == "AUTOROUTE_" + args.delegate.upper() + "_OK" for node in tree.iter("node")), "Delegation result missing from native UI"
            if args.delegate == "image":
                from PIL import Image
                pixels=Image.open(directory/(args.name+".png")).convert("RGB").get_flattened_data()
                assert sum(1 for p in pixels if all(abs(a-b)<=3 for a,b in zip(p,(250,150,40))))>500
            if args.expect_tts:
                deadline=time.monotonic()+30
                while time.monotonic()<deadline:
                    status=root(f"cat /data/user/0/{app}/shared_prefs/switch.xml").decode("utf8")
                    if "原厂 TTS 播放结束（中断=false）" in status:
                        break
                    time.sleep(.2)
                assert "原厂 TTS 播放结束（中断=false）" in status
            if args.previous:
                checkpoint=len(records.read_text(encoding="utf8").splitlines())
                root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService -a com.miui.voiceassist.query "
                     "--es voice_assist_start_from_key xiaoai_switch_fixture --ez use_offline_nlp false --ez isCloseMic true --es assist_query AUTOROUTE_PREVIOUS")
                deadline=time.monotonic()+20
                edited=[]
                while time.monotonic()<deadline:
                    edited=[json.loads(line) for line in records.read_text(encoding="utf8").splitlines()[checkpoint:]]
                    if len(edited)>=3:
                        break
                    time.sleep(.2)
                edit=next(row for row in edited if row["path"].endswith("/images/edits"))
                assert edit["image_inputs"] and edit["body"]["model"]=="FIXTURE_IMAGE"
                time.sleep(1)
                capture(args.name+"-previous")
                pixels=Image.open(directory/(args.name+"-previous.png")).convert("RGB").get_flattened_data()
                assert sum(1 for p in pixels if all(abs(a-b)<=3 for a,b in zip(p,(70,90,220))))>500
            print(json.dumps({"passed": True, "planner": args.route, "delegated": args.delegate, "requests": len(observed)}, ensure_ascii=False))
            return
        if args.route == "image":
            assert request["path"] == "/v1/images/generations"
            assert request["body"]["prompt"] == config[args.route]["systemPrompt"] + "\n\n" + (args.expected_text or args.query)
        else:
            assert request["path"] == "/v1/chat/completions"
            assert request["body"]["messages"][0] == {"role": "system", "content": config[args.route]["systemPrompt"]}
            if args.route != "control" and not args.microphone:
                assert request["body"]["messages"][-1] == {"role": "user", "content": args.expected_text or args.query}
            if args.microphone:
                recognized = request["body"]["messages"][-1]["content"]
                assert re.search("十二|12", recognized) and re.search("六|6", recognized), recognized
                directory.joinpath(args.name + "-recognized.txt").write_text(recognized, encoding="utf-8")
            if args.route == "control":
                assert request["body"]["messages"][1]["content"][0]["text"] == (args.expected_text or args.query)
                assert len(request["body"]["tools"]) == 6
                assert request["screens"] and request["screens"][0]["width"] > 100
                if args.steps == 1:
                    deadline = time.monotonic() + 12
                    while time.monotonic() < deadline:
                        status = root(f"cat /data/user/0/{app}/shared_prefs/switch.xml").decode("utf-8")
                        if "StepLimit" in status:
                            break
                        time.sleep(.2)
                    assert "StepLimit" in status, "Control step limit was not enforced"
                    tree = capture(args.name)
                    assert any("手机控制达到 1 次模型调用上限" in node.get("text", "") for node in tree.iter("node"))
                    assert len(records.read_text(encoding="utf-8").splitlines()) == before + 1
                    assert "XiaoAiSwitch Control Stop" not in root("dumpsys window windows").decode("utf-8")
                    print(json.dumps({"passed": True, "route": "control", "step_limit": 1}, ensure_ascii=False))
                    return
                if args.cancel:
                    dump = root("dumpsys window windows").decode("utf-8")
                    blocks = re.split(r"\n  Window #\d+ ", dump)
                    window = next((block for block in blocks if "XiaoAiSwitch Control Stop" in block.splitlines()[0]), "")
                    bounds = re.search(r"(?:frame|mFrame)=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", window)
                    assert bounds, "Stop control bounds not found"
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    # The stock PAG runs continuously; a UIAutomator idle wait here can outlast the delayed response.
                    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        status = root(f"cat /data/user/0/{app}/shared_prefs/switch.xml").decode("utf-8")
                        if "已停止自定义手机控制" in status:
                            break
                        time.sleep(0.1)
                    assert "已停止自定义手机控制" in status, "Stop button did not cancel task"
                    assert "XiaoAiSwitch Control Stop" not in root("dumpsys window windows").decode("utf-8")
                    time.sleep(8.5)
                    assert len(records.read_text(encoding="utf-8").splitlines()) == before + 1, "Cancelled task made another model call"
                    assert "com.android.settings" not in root("dumpsys activity activities | grep topResumedActivity").decode("utf-8")
                    if args.gesture:
                        assert control_fixture()["count"] == "0", "A gesture arrived after stopping"
                    print(json.dumps({"passed": True, "route": "control", "cancelled": True}, ensure_ascii=False))
                    return
                deadline = time.monotonic() + 35
                while time.monotonic() < deadline:
                    observed = records.read_text(encoding="utf-8").splitlines()
                    if len(observed) >= before + (7 if args.gesture else 4):
                        break
                    time.sleep(0.2)
                assert len(observed) >= before + (7 if args.gesture else 4), "Control loop did not reach expected model calls"
                final = json.loads(observed[before + (6 if args.gesture else 3)])
                results = [json.loads(message["content"]) for message in final["body"]["messages"] if message.get("role") == "tool"]
                assert len(results) == (6 if args.gesture else 3) and all(result.get("ok") for result in results), results
                assert len({screen["sha256"] for screen in final["screens"]}) >= 2, "Screen state never changed"
                if args.gesture:
                    values = control_fixture()
                    directory.joinpath(args.name + "-fixture-state.json").write_text(json.dumps(values, ensure_ascii=False), encoding="utf-8")
                    assert values["count"] == "1", values
                    assert values["text"] == "控制测试XASWITCH", values
                    assert int(values["scroll"]) > 0, values
                    print(json.dumps({"passed": True, "route": "control", "gesture": True}, ensure_ascii=False))
                    return
        time.sleep(1)
        tree = capture(args.name)
        texts = [node.get("text", "") for node in tree.iter("node")]
        if args.route != "image":
            assert ("CONTROL_FIXTURE_OK" if args.route == "control" else "XASWITCH_FIXTURE_OK") in texts, "Reply is missing from native card"
        if args.route == "image":
            assert (args.expected_text or args.query) in texts, "Image description is missing from native Gallery"
            from PIL import Image
            pixels = Image.open(directory / (args.name + ".png")).convert("RGB").get_flattened_data()
            assert sum(1 for color in pixels if all(abs(a - b) <= 2 for a, b in zip(color, (250, 150, 40)))) > 500, "Orange checkerboard was not rendered"
            assert not any("来源淘宝" in text for text in texts), "Native gallery shows the wrong provider attribution"
            if args.preview:
                nodes = [node for node in tree.iter("node") if node.get("resource-id", "").endswith(":id/imageview")]
                assert nodes, "Gallery image click target not found"
                coords = [int(value) for value in re.findall(r"\d+", nodes[0].get("bounds"))]
                adb("shell", "input", "keyevent", "224")
                adb("shell", "input", "tap", str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))
                time.sleep(0.5)
                preview = capture(args.name + "-preview")
                assert any(node.get("content-desc") == "自定义绘图预览" for node in preview.iter("node")), "Preview is not visible"
                pixels = Image.open(directory / (args.name + "-preview.png")).convert("RGB").get_flattened_data()
                assert sum(1 for color in pixels if all(abs(a - b) <= 2 for a, b in zip(color, (250, 150, 40)))) > 100000, "Enlarged image did not render"
                adb("shell", "input", "keyevent", "4")
        if args.expect_tts:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                status = root(f"cat /data/user/0/{app}/shared_prefs/switch.xml").decode("utf-8")
                if "原厂 TTS 播放结束（中断=false）" in status:
                    break
                time.sleep(0.25)
            assert "原厂 TTS 播放结束（中断=false）" in status, "Native TTS did not complete playback"
        status = root(f"cat /data/user/0/{app}/shared_prefs/switch.xml").decode("utf-8")
        directory.joinpath(args.name + "-status.xml").write_text(status, encoding="utf-8")
        print(json.dumps({"passed": True, "route": args.route, "model": request["body"]["model"],
                          "path": request["path"], "ui": args.name, "preview": args.preview}, ensure_ascii=False))
    finally:
        directory.joinpath(args.name + "-status.xml").write_bytes(root(f"cat /data/user/0/{app}/shared_prefs/switch.xml"))
        adb("shell", "am", "force-stop", app)
        path.write_bytes(backup)
        adb("push", str(path), "/data/local/tmp/xa-current-prefs.xml")
        adb("shell", "run-as", app, "cp", "/data/local/tmp/xa-current-prefs.xml", "shared_prefs/switch.xml")
        adb("reverse", "--remove", "tcp:8765")


if __name__ == "__main__":
    main()
