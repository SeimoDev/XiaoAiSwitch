"""Observe the production stock PAG effect during real control/cancel fixtures."""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--records", required=True)
    parser.add_argument("--gesture", action="store_true")
    args = parser.parse_args()
    prefix = ["adb", "-s", args.serial]

    def adb(*command):
        return subprocess.run(prefix + list(command), capture_output=True, check=True).stdout

    adb("shell", "am", "force-stop", "com.miui.voiceassist")
    adb("shell", "input", "keyevent", "3")
    records = Path(args.records)
    before = len(records.read_text(encoding="utf8").splitlines()) if records.exists() else 0
    name = "stock-effect-gesture-observed" if args.gesture else "stock-effect-cancel"
    process = subprocess.Popen([sys.executable, "-X", "utf8", str(ROOT / "scripts/device_fixture_test.py"),
        "--serial", args.serial, "--route", "control", "--query", "CONTROL_GESTURE" if args.gesture else "CONTROL_CANCEL", "--name", name,
        "--records", args.records, "--gesture" if args.gesture else "--cancel"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        deadline = time.monotonic() + 22
        while time.monotonic() < deadline:
            windows = adb("shell", "dumpsys", "window", "windows").decode("utf8")
            if "XiaoAiSwitch Control Stop" in windows:
                break
            time.sleep(.15)
        assert "XiaoAiSwitch Control Stop" in windows, "Control job did not enter its interactive phase"
        assert "UIAgentScreenMask" in windows, "Stock effect window is missing"
        mask = next(block for block in re.split(r"\n\s*Window #\d+ ", windows) if "UIAgentScreenMask" in block.splitlines()[0])
        assert "NOT_TOUCHABLE" in mask and "TRUSTED_OVERLAY" in mask
        ROOT.joinpath("evidence/stock-effect-windows.txt").write_text(windows, encoding="utf8")
        time.sleep(1.1)
        ROOT.joinpath("evidence/" + ("stock-effect-gesture-inflight.png" if args.gesture else "stock-effect-inflight.png")).write_bytes(adb("exec-out", "screencap", "-p"))
        output, _ = process.communicate(timeout=55)
        print(output.decode("utf8"), end="")
        assert process.returncode == 0, "Control fixture failed"
        assert "UIAgentScreenMask" not in adb("shell", "dumpsys", "window", "windows").decode("utf8"), "Stock effect remained after cancel"
        logs = adb("logcat", "-d", "-s", "XiaoAiSwitch:I", "*:S").decode("utf8")
        assert "StockControlEffect started" in logs and "StockControlEffect stopped" in logs
        ROOT.joinpath("evidence/stock-effect-runtime.log").write_text(logs, encoding="utf8")
        if args.gesture:
            from PIL import Image
            rows = [json.loads(line) for line in records.read_text(encoding="utf8").splitlines()[before:]]
            screen = rows[-1]["screens"][-1]
            image = Image.open(ROOT / ("evidence/control-screen-" + screen["sha256"][:16] + ".jpg")).convert("RGB")
            saturation = max(max(image.getpixel((x,y))) - min(image.getpixel((x,y)))
                             for x in [0, 3, 10] for y in range(100, image.height - 60))
            assert saturation < 8, "Stock glow leaked into the model screenshot edges"
            ROOT.joinpath("evidence/stock-effect-screenshot-proof.json").write_text(json.dumps({
                "passed": True, "sha256": screen["sha256"], "edge_max_saturation": saturation,
                "window_type": int(re.search(r"type=(\d+)", mask).group(1)),
                "trusted_overlay": True, "not_touchable": True}), encoding="utf8")
        print("Stock native PAG window, animation lifecycle and cleanup: passed")
    finally:
        if process.poll() is None:
            output, _ = process.communicate(timeout=60)
            print(output.decode("utf8"), end="")
        elif process.stdout is not None and not process.stdout.closed:
            print(process.stdout.read().decode("utf8"), end="")


if __name__ == "__main__":
    main()
