"""Verify the production crop/coordinate mapping against a local Android emulator window."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--landscape", action="store_true")
    args = parser.parse_args()
    if re.fullmatch(r"emulator-\d+", args.serial) is None:
        parser.error("This test only targets a local emulator")

    def adb(*command):
        return subprocess.run(["adb", "-s", args.serial, *command], check=True,
                              capture_output=True, timeout=30).stdout

    def fixture():
        data = adb("shell", "run-as", "io.github.xiaoaiswitch", "cat", "shared_prefs/control_fixture.xml")
        return {node.get("name"): node.get("value", node.text) for node in ET.fromstring(data)}

    app = "io.github.xiaoaiswitch"
    previous_rotation = adb("shell", "cmd", "window", "user-rotation").decode("ascii").strip().split()
    if previous_rotation != ["free"] and (len(previous_rotation) != 2 or previous_rotation[0] != "lock"
                                         or previous_rotation[1] not in ("0", "1", "2", "3")):
        raise AssertionError("Unexpected emulator rotation state")
    adb("shell", "input", "keyevent", "224")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "cmd", "window", "user-rotation", "lock", "1" if args.landscape else "0")
    try:
        adb("shell", "am", "force-stop", app)
        adb("shell", "am", "start", "-W", "-n", app + "/.ControlFixtureActivity", "--ez", "viewport_test", "true")
        until = time.monotonic() + 12
        while True:
            state = fixture()
            if "viewportError" in state:
                raise AssertionError(state["viewportError"])
            if "projectedY" in state and (int(state["displayWidth"]) > int(state["displayHeight"])) == args.landscape:
                break
            if time.monotonic() >= until:
                raise AssertionError("Fixture has not recorded the actual display viewport")
            time.sleep(.2)
        left, top, right, bottom = map(int, state["controlViewport"].split(","))
        assert 0 < top and top == int(state["projectedY"]), state
        assert int(state["cropWidth"]) == right - left and int(state["cropHeight"]) == bottom - top, state
        assert state["cropContainsOnlyContent"] == "true", state
        adb("shell", "input", "tap", state["projectedX"], state["projectedY"])
        until = time.monotonic() + 3
        while True:
            received = fixture()
            if received.get("lastTouchY") == state["projectedY"] and received.get("lastTouchX") == state["projectedX"]:
                break
            if time.monotonic() >= until:
                raise AssertionError("Projected first content row did not reach the fixture app")
            time.sleep(.1)
        print(json.dumps({"emulator": args.serial, "landscape": args.landscape,
                          "sdk": int(adb("shell", "getprop", "ro.build.version.sdk")),
                          "display": [int(state["displayWidth"]), int(state["displayHeight"])],
                          "viewport": [left, top, right, bottom],
                          "app_received": [int(received["lastTouchX"]), int(received["lastTouchY"])]}))
    finally:
        adb("shell", "cmd", "window", "user-rotation", *previous_rotation)
