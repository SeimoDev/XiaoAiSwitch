"""Verify two native full-screen query rows and two distinct real loopback SSE replies."""
import argparse
import hashlib
import json
import re
import shlex
import struct
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP = "io.github.xiaoaiswitch"
ASSISTANT = "com.miui.voiceassist"
FULLSCREEN = "com.xiaomi.voiceassistant.TabHostMainActivity"
PORT = 8767
PREFS = "shared_prefs/switch.xml"
BACKUP_PREFS = PREFS + ".bak"
MODEL = "FIXTURE_CONVERSATION"
TOKEN = "LOCAL_CONVERSATION_FIXTURE"


class Turn:
    def __init__(self, index, nonce):
        self.index = index
        self.query = f"XASW_Q{index}_{nonce}"
        self.first = f"XASW_R{index}_FIRST_{nonce}"
        self.final = f"XASW_R{index}_FINAL_{nonce}"
        self.first_sent = threading.Event()
        self.release_final = threading.Event()
        self.final_sent = threading.Event()
        self.accepted = False
        self.times = {}
        self.history = {}


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, turns, port=PORT):
        super().__init__(("127.0.0.1", port), StreamHandler)
        self.turns = turns
        self.request_lock = threading.Lock()
        self.aborted = threading.Event()

    def stop_waiting(self):
        self.aborted.set()
        for turn in self.turns:
            turn.release_final.set()


class StreamHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        size = self.headers.get("Content-Length", "")
        if (self.path != "/v1/chat/completions" or not size.isascii() or not size.isdecimal()
                or len(size) > 6 or not 0 < int(size) <= 131072):
            self.send_error(400)
            return
        try:
            request = json.loads(self.rfile.read(int(size)))
            messages = request["messages"]
            message = messages[-1]
            turn = next((item for item in self.server.turns if message["content"] == item.query), None)
            valid = (turn is not None and request["model"] == MODEL and request["stream"] is True
                     and message["role"] == "user"
                     and self.headers.get("Authorization") == "Bearer " + TOKEN)
        except (ValueError, KeyError, IndexError, TypeError):
            self.send_error(400)
            return
        if not valid:
            self.send_error(422)
            return
        with self.server.request_lock:
            previous = self.server.turns[:turn.index - 1]
            if turn.accepted or not all(item.final_sent.is_set() for item in previous):
                self.send_error(409)
                return
            turn.accepted = True
            if previous:
                last = previous[-1]
                turn.history = {
                    "previous_query_present": any(item.get("role") == "user" and item.get("content") == last.query
                                                  for item in messages[:-1]),
                    "previous_reply_present": any(item.get("role") == "assistant"
                                                  and item.get("content") == last.first + "\n\n" + last.final
                                                  for item in messages[:-1]),
                }
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def chunk(delta, finish=None):
            event = {"id": f"local-conversation-{turn.index}", "object": "chat.completion.chunk", "model": MODEL,
                     "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.wfile.write(("data: " + json.dumps(event) + "\n\n").encode("utf-8"))
            self.wfile.flush()

        try:
            chunk({"role": "assistant", "content": turn.first})
            turn.times["first_flush"] = time.perf_counter()
            turn.first_sent.set()
            if not turn.release_final.wait(45) or self.server.aborted.is_set():
                return
            chunk({"content": "\n\n" + turn.final})
            chunk({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            turn.times["final_flush"] = time.perf_counter()
            turn.final_sent.set()
        except (BrokenPipeError, ConnectionResetError):
            return


def marker_evidence(xml, markers, width, height):
    """Retain only our synthetic markers and geometry, never arbitrary hierarchy text."""
    result = {marker: [] for marker in markers}
    for node in ET.fromstring(xml).iter("node"):
        if node.get("package") != ASSISTANT:
            continue
        bounds = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if bounds is None:
            continue
        left, top, right, bottom = map(int, bounds.groups())
        if not (left < right and top < bottom and right > 0 and bottom > 0 and left < width and top < height):
            continue
        if node.get("visible-to-user") == "false" or "EditText" in node.get("class", ""):
            continue
        for attribute in ("text", "content-desc"):
            value = node.get(attribute, "")
            for marker in markers:
                if marker not in value:
                    continue
                entry = {"bounds": [left, top, right, bottom], "attribute": attribute}
                resource = node.get("resource-id", "")
                if resource.startswith(ASSISTANT + ":id/"):
                    entry["resource_id"] = resource
                if entry not in result[marker]:
                    result[marker].append(entry)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--nonce", default=uuid.uuid4().hex[:10], help="6-24 ASCII letters/digits; also names evidence.")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--screenshots-only", action="store_true", help="Check transport and collect screenshots for manual row verification.")
    args = parser.parse_args()
    if re.fullmatch(r"[A-Za-z0-9]{6,24}", args.nonce) is None or not 1024 <= args.port <= 65535:
        parser.error("Use a 6-24 character ASCII alphanumeric nonce and an unprivileged TCP port")
    evidence = ROOT / "evidence" / ("native-conversation-" + args.nonce)
    evidence.mkdir(parents=True, exist_ok=False)
    turns = [Turn(1, args.nonce), Turn(2, args.nonce)]
    markers = [marker for turn in turns for marker in (turn.query, turn.first, turn.final)]
    receipt = {"passed": False, "nonce": args.nonce, "native_entry": "SpeechQueryService",
               "required_fullscreen": FULLSCREEN, "evidence": evidence.relative_to(ROOT).as_posix(), "turns": []}

    def adb(*command, data=None):
        return subprocess.run(["adb", "-s", args.serial, *command], input=data, check=True,
                              capture_output=True, timeout=30).stdout

    def run_as(command, data=None):
        return adb("shell", "-T", f"run-as {APP} sh -c {shlex.quote(command)}", data=data)

    def root(command):
        return adb("shell", "-T", "su -c " + shlex.quote(command))

    def snapshot(path):
        result = run_as(f"if [ -f {path} ]; then printf y; cat {path}; else printf n; fi")
        if result == b"n":
            return None
        if result.startswith(b"y"):
            return result[1:]
        raise RuntimeError("Unexpected in-memory preferences snapshot response")

    def restore(original):
        adb("shell", "am", "force-stop", APP)
        for path, data in original.items():
            if data is None:
                run_as(f"rm -f {path}")
            else:
                run_as(f"cat > {path}", data)

    def fullscreen():
        windows = adb("shell", "dumpsys", "window", "displays").decode("utf-8", errors="replace")
        return any("mCurrentFocus=" in line and ASSISTANT + "/" + FULLSCREEN in line
                   for line in windows.splitlines())

    def wait_fullscreen():
        until = time.monotonic() + 8
        while time.monotonic() < until:
            if fullscreen():
                return
            time.sleep(.2)
        raise AssertionError("Original TabHostMainActivity is not the current full-screen focus")

    def capture(name):
        wait_fullscreen()
        data = adb("exec-out", "screencap", "-p")
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError("Device did not return a PNG")
        (evidence / (name + ".png")).write_bytes(data)
        return {"png_sha256": hashlib.sha256(data).hexdigest(), "captured_at": time.perf_counter(),
                "size": struct.unpack(">II", data[16:24]), "fullscreen_focus": True}

    def ui_markers(width, height):
        remote = "/data/local/tmp/xasw-conversation-" + args.nonce + ".xml"
        try:
            adb("shell", "uiautomator", "dump", "--compressed", remote)
            xml = adb("exec-out", "cat", remote)
            return marker_evidence(xml, markers, width, height)
        finally:
            adb("shell", "rm", "-f", remote)

    try:
        with FixtureServer(turns, args.port) as server, ExitStack() as cleanup:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            cleanup.callback(server.shutdown)
            cleanup.callback(server.stop_waiting)
            adb("reverse", "--no-rebind", f"tcp:{args.port}", f"tcp:{args.port}")
            cleanup.callback(adb, "reverse", "--remove", f"tcp:{args.port}")
            adb("shell", "am", "force-stop", APP)
            original = {path: snapshot(path) for path in (PREFS, BACKUP_PREFS)}
            cleanup.callback(restore, original)
            config = {
                "enabled": True, "autoRoute": False, "systemTools": False, "defaultRoute": "chat",
                "revision": int(time.time() * 1000),
                "providers": {"fixture": {"name": "fixture", "provider": "openai_compatible",
                                          "endpoint": f"http://127.0.0.1:{args.port}/v1", "token": TOKEN}},
                "chat": {"enabled": True, "providerId": "fixture", "model": MODEL},
            }
            settings = ET.Element("map")
            ET.SubElement(settings, "string", name="config").text = json.dumps(config)
            run_as(f"mkdir -p shared_prefs && rm -f {BACKUP_PREFS} && cat > {PREFS}",
                   ET.tostring(settings, encoding="utf-8", xml_declaration=True))
            adb("shell", "am", "force-stop", ASSISTANT)
            cleanup.callback(adb, "shell", "am", "force-stop", ASSISTANT)
            adb("shell", "input", "keyevent", "224")
            adb("shell", "wm", "dismiss-keyguard")
            adb("shell", "am", "start", "-W", "-n", ASSISTANT + "/com.xiaomi.voiceassistant.MainActivity")
            wait_fullscreen()

            for turn in turns:
                wait_fullscreen()
                root(f"am startservice -n {ASSISTANT}/com.xiaomi.voiceassistant.SpeechQueryService "
                     "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
                     f"--ez isCloseMic true --ez use_offline_nlp false --es assist_query {shlex.quote(turn.query)}")
                if not turn.first_sent.wait(15):
                    raise AssertionError(f"Turn {turn.index} did not receive an accepted SSE request")
                time.sleep(1.2)
                first = capture(f"turn-{turn.index}-first")
                if turn.release_final.is_set() or turn.final_sent.is_set():
                    raise AssertionError("Final SSE content was not gated through its first screenshot")
                turn.release_final.set()
                if not turn.final_sent.wait(5):
                    raise AssertionError(f"Turn {turn.index} did not flush its final SSE event")
                time.sleep(1.8)
                final = capture(f"turn-{turn.index}-final")
                visible = {marker: [] for marker in markers}
                ui_error = "not_requested"
                if not args.screenshots_only:
                    try:
                        visible = ui_markers(*final["size"])
                        ui_error = None
                    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError) as error:
                        # Still exercise turn two; screenshots remain reviewable if accessibility is unavailable.
                        ui_error = type(error).__name__
                start = turn.times["first_flush"]
                receipt["turns"].append({
                    "index": turn.index, "query_marker": turn.query, "first_marker": turn.first, "final_marker": turn.final,
                    "stream_request_accepted": turn.accepted,
                    "first_capture_before_final": start <= first["captured_at"] < turn.times["final_flush"],
                    "final_capture_after_final": turn.times["final_flush"] <= final["captured_at"],
                    "first_capture_ms": round((first["captured_at"] - start) * 1000),
                    "final_flush_ms": round((turn.times["final_flush"] - start) * 1000),
                    "first_png_sha256": first["png_sha256"], "final_png_sha256": final["png_sha256"],
                    "fullscreen_focus": True, "visible_synthetic_nodes": visible, "provider_history": turn.history,
                    "hierarchy_available": ui_error is None, "hierarchy_error": ui_error,
                })

            last = receipt["turns"][-1]["visible_synthetic_nodes"]
            first_ui = receipt["turns"][0]["visible_synthetic_nodes"]
            query_one_bounds = {tuple(item["bounds"]) for item in last[turns[0].query]}
            query_two_bounds = {tuple(item["bounds"]) for item in last[turns[1].query]}
            transport = {
                "two_distinct_native_queries_accepted": all(turn.accepted for turn in turns),
                "two_genuine_gated_streams": all(item["first_capture_before_final"] and item["final_capture_after_final"]
                                                for item in receipt["turns"]),
                "previous_exchange_in_model_context": all(turns[1].history.values()) and bool(turns[1].history),
            }
            visual = {
                "first_query_row_visible_after_first": bool(first_ui[turns[0].query]),
                "first_distinct_reply_visible_after_first": bool(first_ui[turns[0].first] and first_ui[turns[0].final]),
                "both_query_rows_visible_after_second": bool(query_one_bounds and query_two_bounds),
                "query_rows_have_distinct_geometry": bool(query_one_bounds and query_two_bounds
                                                         and query_one_bounds.isdisjoint(query_two_bounds)),
                "both_distinct_replies_visible_after_second": all(last[turn.first] and last[turn.final] for turn in turns),
            }
            checks = transport if args.screenshots_only else {**transport, **visual}
            receipt["checks"] = checks
            receipt["passed"] = None if args.screenshots_only and all(transport.values()) else all(checks.values())
            receipt["visual_verification"] = "manual_review_required" if args.screenshots_only else "hierarchy_and_screenshots"
            if receipt["passed"] is False:
                receipt["failure"] = "Native rows/replies did not satisfy: " + ", ".join(key for key, value in checks.items() if not value)
    except Exception as error:
        receipt["passed"] = False
        receipt["failure"] = str(error) if isinstance(error, AssertionError) else type(error).__name__
    (evidence / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False))
    if receipt["passed"] is False:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
