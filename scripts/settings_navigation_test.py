"""Check press feedback and the toolbar back button on the real settings screens."""

import argparse
import io
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops

APP = "io.github.xiaoaiswitch"
LAYOUT = "/data/local/tmp/xasw-navigation-test.xml"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--predictive", action="store_true", help="Exercise Android 14+ edge gestures in gesture navigation mode")
    args = parser.parse_args()

    def adb(*command):
        return subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, check=True).stdout

    def node(tag):
        for _ in range(3):
            message = adb("shell", "uiautomator", "dump", "--compressed", LAYOUT)
            if b"dumped to" not in message:
                continue
            layout = ET.fromstring(adb("shell", "cat", LAYOUT))
            for item in layout.iter("node"):
                if item.get("resource-id") == tag:
                    return item
            time.sleep(0.25)
        raise AssertionError(f"没有找到界面节点：{tag}")

    def bounds(item):
        return tuple(map(int, re.findall(r"\d+", item.get("bounds"))))

    def tap_at(rect):
        return str((rect[0] + rect[2]) // 2), str((rect[1] + rect[3]) // 2)

    def screen():
        return Image.open(io.BytesIO(adb("exec-out", "screencap", "-p"))).convert("RGB")

    adb("shell", "input", "keyevent", "224")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", APP + "/.SettingsActivity")
    try:
        row = bounds(node("menu_chat"))
        time.sleep(0.35)
        initial = screen()
        adb("shell", "input", "motionevent", "DOWN", *tap_at(row))
        try:
            time.sleep(0.25)
            pressed = screen()
        finally:
            adb("shell", "input", "motionevent", "CANCEL", *tap_at(row))
        assert ImageChops.difference(initial.crop(row), pressed.crop(row)).getbbox(), "No row press feedback"
        adb("shell", "input", "tap", *tap_at(bounds(node("menu_chat"))))
        back = node("settings_back")
        assert back.get("content-desc") == "返回", "Toolbar back action has no accessible name"
        density = int(re.findall(r"\d+", adb("shell", "wm", "density").decode())[-1])
        rect = bounds(back)
        assert min(rect[2] - rect[0], rect[3] - rect[1]) >= 48 * density / 160 - 1
        adb("shell", "input", "tap", *tap_at(rect))
        node("menu_chat")
        if args.predictive:
            adb("shell", "input", "tap", *tap_at(bounds(node("menu_chat"))))
            node("settings_back")
            time.sleep(0.35)
            current = screen()
            width, height = current.size
            y = str(height // 2)
            adb("shell", "input", "motionevent", "DOWN", "2", y)
            try:
                for position in (24, 80, width // 8, width // 5):
                    adb("shell", "input", "motionevent", "MOVE", str(position), y)
                time.sleep(0.18)
                preview = screen()
            finally:
                adb("shell", "input", "motionevent", "CANCEL", str(width // 5), y)
            content = (0, height // 10, width, height * 8 // 10)
            assert ImageChops.difference(current.crop(content), preview.crop(content)).getbbox(), "No in-app back preview"
            node("settings_back")
            adb("shell", "input", "swipe", "2", y, str(width // 2), y, "450")
            node("menu_chat")
            print("Predictive back passed: in-gesture preview, cancellation and committed return")
        print("Navigation test passed: press feedback, 48dp back action, and click to return")
    finally:
        adb("shell", "rm", "-f", LAYOUT)


if __name__ == "__main__":
    main()
