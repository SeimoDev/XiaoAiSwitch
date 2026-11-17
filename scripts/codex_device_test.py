"""Verify phone-owned Codex credentials through the real XiaoAi hook, without reading tokens."""
import argparse
import json
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from fixture_config import with_registry

ROOT = Path(__file__).resolve().parents[1]
APP = "io.github.xiaoaiswitch"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--route", choices=["chat", "voice", "control"], default="chat")
    parser.add_argument("--delegate-image", action="store_true")
    parser.add_argument("--registry", action="store_true")
    parser.add_argument("--records", default=str(ROOT / "evidence/round5-requests.jsonl"))
    args = parser.parse_args()
    prefix = ["adb", "-s", args.serial]
    directory = ROOT / "evidence"
    directory.mkdir(exist_ok=True)
    name = "codex-live-" + args.route + ("-image" if args.delegate_image else "")

    def adb(*command):
        return subprocess.run(prefix + list(command), capture_output=True, check=True).stdout

    def root(command):
        return adb("shell", "su -c " + shlex.quote(command))

    def status():
        tree = ET.fromstring(root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml"))
        return next((node.text or "" for node in tree if node.get("name") == "status"), "")

    def capture(suffix=""):
        adb("shell", "uiautomator dump /sdcard/xa-codex-test.xml")
        xml = adb("shell", "cat /sdcard/xa-codex-test.xml")
        directory.joinpath(name + suffix + ".xml").write_bytes(xml)
        directory.joinpath(name + suffix + ".png").write_bytes(adb("exec-out", "screencap", "-p"))
        return ET.fromstring(xml)

    backup = root(f"cat /data/user/0/{APP}/shared_prefs/switch.xml")
    marker = "CODEX_" + args.route.upper() + "_OK"
    prompt = "这是手机端订阅直连验证。只回复 " + marker + "，不添加其他内容。"
    query = "请按系统提示回复。"
    if args.route == "control":
        prompt = ("这是手机工具测试。先且仅调用一次 screenshot 工具；不要点击、输入或打开其他应用。"
                  "收到工具结果和新截图后，若看到 Control Fixture 测试页，就只回复 " + marker + "。")
        query = "请核验截图工具和当前测试页，然后结束。"
    if args.delegate_image:
        assert args.route != "control"
        prompt = ("当用户请求绘图时，必须且仅调用一次 generate_image 工具，prompt 为 CODEX_IMAGE_TEST，image 为 none。"
                  "工具成功后，只回复 " + marker + "。")
        query = "请画一只猫。"
    config = {"enabled": True, "revision": int(time.time() * 1000), "defaultRoute": args.route,
              "autoRoute": False, "controlSteps": 3,
              args.route: {"enabled": True, "provider": "codex_subscription", "model": args.model,
                           "systemPrompt": prompt}}
    records = Path(args.records)
    before = len(records.read_text(encoding="utf8").splitlines()) if records.exists() else 0
    if args.delegate_image:
        config["autoRoute"] = True
        config["image"] = {"enabled": True, "endpoint": "http://127.0.0.1:8765/image/v1",
                           "token": "FIXTURE_IMAGE_TOKEN", "model": "FIXTURE_IMAGE", "systemPrompt": "IMAGE_SYSTEM"}
    if args.registry:
        with_registry(config)
    prefs = ET.Element("map")
    ET.SubElement(prefs, "string", name="config").text = json.dumps(config, ensure_ascii=False)
    temporary = directory / "codex-test-prefs.xml"
    ET.ElementTree(prefs).write(temporary, encoding="utf-8", xml_declaration=True)
    try:
        adb("shell", "am", "force-stop", APP)
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("push", str(temporary), "/data/local/tmp/xa-codex-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xa-codex-prefs.xml", "shared_prefs/switch.xml")
        if args.delegate_image:
            adb("reverse", "tcp:8765", "tcp:8765")
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        if args.route == "control":
            adb("shell", "am", "start", "-W", "-n", APP + "/.ControlFixtureActivity")
        root("am start -W -n com.miui.voiceassist/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        root("am startservice -n com.miui.voiceassist/com.xiaomi.voiceassistant.SpeechQueryService "
             "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
             "--ez use_offline_nlp false --ez isCloseMic true --es assist_query " + shlex.quote(query))
        deadline = time.monotonic() + 150
        observed = []
        while time.monotonic() < deadline:
            value = status()
            if not observed or observed[-1] != value:
                observed.append(value)
                print(value, flush=True)
            if "失败" in value or "已结束" in value or "请求成功" in value or "TTS 播放结束" in value:
                break
            time.sleep(.4)
        tree = capture()
        texts = [node.get("text", "") for node in tree.iter("node")]
        assert any(marker == text for text in texts), {"status": status(), "visible": texts[-14:]}
        if args.delegate_image:
            from PIL import Image
            requests = [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()[before:]]
            assert len(requests) == 1 and requests[0]["path"] == "/image/v1/images/generations", requests
            assert requests[0]["fixture_token_matches"] and requests[0]["body"]["prompt"] == "IMAGE_SYSTEM\n\nCODEX_IMAGE_TEST"
            pixels = Image.open(directory / (name + ".png")).convert("RGB").get_flattened_data()
            assert sum(1 for pixel in pixels if all(abs(a-b) <= 3 for a,b in zip(pixel,(250,150,40)))) > 500
        if args.route == "control":
            assert any("模型调用：2" in value for value in observed), "Tool result was not followed by another model call"
            assert "XiaoAiSwitch Control Stop" not in root("dumpsys window windows").decode("utf8")
        if args.route == "voice":
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline and "原厂 TTS 播放结束（中断=false）" not in status():
                time.sleep(.3)
            assert "原厂 TTS 播放结束（中断=false）" in status(), "Native TTS did not finish"
        directory.joinpath(name + "-result.json").write_text(json.dumps({
            "passed": True, "model": args.model, "route": args.route, "status": status(),
            "observed": observed, "transport": "phone-direct", "credentials_exported": False,
        }, ensure_ascii=False, indent=2), encoding="utf8")
        print(json.dumps({"passed": True, "model": args.model, "route": args.route}, ensure_ascii=False))
    finally:
        adb("shell", "am", "force-stop", "com.miui.voiceassist")
        adb("shell", "am", "force-stop", APP)
        temporary.write_bytes(backup)
        adb("push", str(temporary), "/data/local/tmp/xa-codex-prefs.xml")
        adb("shell", "run-as", APP, "cp", "/data/local/tmp/xa-codex-prefs.xml", "shared_prefs/switch.xml")
        adb("shell", "rm", "/data/local/tmp/xa-codex-prefs.xml")
        if args.delegate_image:
            adb("reverse", "--remove", "tcp:8765")
        temporary.unlink()


if __name__ == "__main__":
    main()
