"""Capture the native card before and after a gated loopback SSE response."""
import argparse
import hashlib
import json
import shlex
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
PORT = 8766
PREFS = "shared_prefs/switch.xml"
BACKUP_PREFS = PREFS + ".bak"
MODEL = "FIXTURE_STREAM"
TOKEN = "LOCAL_STREAM_FIXTURE"
FIRST = "STREAM FIRST 82"
FINAL = "STREAM FINAL 82"


class FixtureServer(ThreadingHTTPServer):
    def __init__(self, query, port=PORT):
        super().__init__(("127.0.0.1", port), StreamHandler)
        self.query = query
        self.first_sent = threading.Event()
        self.release_final = threading.Event()
        self.final_sent = threading.Event()
        self.aborted = threading.Event()
        self.request_lock = threading.Lock()
        self.accepted = 0
        self.times = {}

    def stop_waiting(self):
        self.aborted.set()
        self.release_final.set()


class StreamHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        size = self.headers.get("Content-Length", "")
        if (self.path != "/v1/chat/completions" or not size.isascii() or not size.isdecimal()
                or len(size) > 5 or not 0 < int(size) <= 65536):
            self.send_error(400)
            return
        try:
            request = json.loads(self.rfile.read(int(size)))
            message = request["messages"][-1]
            valid = (request["model"] == MODEL and request["stream"] is True
                     and message["role"] == "user" and message["content"] == self.server.query
                     and self.headers.get("Authorization") == "Bearer " + TOKEN)
        except (ValueError, KeyError, IndexError, TypeError):
            self.send_error(400)
            return
        if not valid:
            self.send_error(422)
            return
        with self.server.request_lock:
            if self.server.accepted:
                self.send_error(409)
                return
            self.server.accepted = 1
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def chunk(delta, finish=None):
            envelope = {"id": "local-stream-fixture", "object": "chat.completion.chunk", "model": MODEL,
                        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.wfile.write(("data: " + json.dumps(envelope) + "\n\n").encode("utf-8"))
            self.wfile.flush()

        try:
            chunk({"role": "assistant", "content": FIRST})
            self.server.times["first_flush"] = time.perf_counter()
            self.server.first_sent.set()
            if not self.server.release_final.wait(25) or self.server.aborted.is_set():
                return
            chunk({"content": "\n\n" + FINAL})
            chunk({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            self.server.times["final_flush"] = time.perf_counter()
            self.server.final_sent.set()
        except (BrokenPipeError, ConnectionResetError):
            return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--voice", action="store_true", help="Exercise the voice channel and verify finished native TTS.")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex[:10]
    evidence = ROOT / "evidence" / ("native-stream-" + nonce)
    query = "XASW_NATIVE_STREAM_QUERY_" + nonce
    route = "voice" if args.voice else "chat"
    native_query = "/voice " + query if args.voice else query

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
        raise RuntimeError("Unexpected preferences snapshot response")

    def restore(original):
        adb("shell", "am", "force-stop", APP)
        for path, data in original.items():
            if data is None:
                run_as(f"rm -f {path}")
            else:
                run_as(f"cat > {path}", data)

    def capture(name):
        data = adb("exec-out", "screencap", "-p")
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError("Device did not return a PNG")
        destination = evidence / (name + ".png")
        destination.write_bytes(data)
        return hashlib.sha256(data).hexdigest()

    with FixtureServer(query) as server, ExitStack() as cleanup:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        cleanup.callback(server.shutdown)
        cleanup.callback(server.stop_waiting)
        adb("reverse", "--no-rebind", f"tcp:{PORT}", f"tcp:{PORT}")
        cleanup.callback(adb, "reverse", "--remove", f"tcp:{PORT}")

        adb("shell", "am", "force-stop", APP)
        original = {path: snapshot(path) for path in (PREFS, BACKUP_PREFS)}
        cleanup.callback(restore, original)
        config = {
            "enabled": True, "autoRoute": False, "systemTools": False, "defaultRoute": "chat",
            "revision": int(time.time() * 1000),
            "providers": {"fixture": {"name": "fixture", "provider": "openai_compatible",
                                      "endpoint": f"http://127.0.0.1:{PORT}/v1", "token": TOKEN}},
            route: {"enabled": True, "providerId": "fixture", "model": MODEL},
        }
        settings = ET.Element("map")
        ET.SubElement(settings, "string", name="config").text = json.dumps(config)
        run_as(f"mkdir -p shared_prefs && rm -f {BACKUP_PREFS} && cat > {PREFS}",
               ET.tostring(settings, encoding="utf-8", xml_declaration=True))
        adb("shell", "am", "force-stop", ASSISTANT)
        cleanup.callback(adb, "shell", "am", "force-stop", ASSISTANT)
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        root(f"am start -W -n {ASSISTANT}/com.xiaomi.voiceassistant.mainui.home.FloatActivity")
        time.sleep(0.8)
        root(f"am startservice -n {ASSISTANT}/com.xiaomi.voiceassistant.SpeechQueryService "
             "-a com.miui.voiceassist.query --es voice_assist_start_from_key xiaoai_switch_fixture "
             f"--ez isCloseMic true --ez use_offline_nlp false --es assist_query {shlex.quote(native_query)}")
        if not server.first_sent.wait(15):
            raise AssertionError("No accepted stream produced a first SSE event")
        time.sleep(1.5)
        evidence.mkdir(parents=True)
        first = capture("first")
        first_capture = time.perf_counter()
        if server.release_final.is_set() or server.final_sent.is_set():
            raise AssertionError("Final event was not held through the first capture")
        server.release_final.set()
        if not server.final_sent.wait(5):
            raise AssertionError("Fixture did not flush its final SSE event")
        time.sleep(1.8)
        final = capture("final")
        final_capture = time.perf_counter()
        tts_finished = False
        if args.voice:
            until = time.monotonic() + 12
            while time.monotonic() < until:
                state = ET.fromstring(snapshot(PREFS))
                status = next((el.text for el in state if el.get("name") == "status"), "")
                if status and "原厂 TTS 播放结束（中断=false）" in status:
                    tts_finished = True
                    break
                time.sleep(.25)
            assert tts_finished, "Native voice playback did not complete"
        start = server.times["first_flush"]
        receipt = {
            "stream_request_accepted": server.accepted == 1,
            "route": route, "tts_finished": tts_finished,
            "captured_before_final": start <= first_capture < server.times["final_flush"],
            "captured_after_final": server.times["final_flush"] <= final_capture,
            "first_capture_ms": round((first_capture - start) * 1000),
            "final_flush_ms": round((server.times["final_flush"] - start) * 1000),
            "final_capture_ms": round((final_capture - start) * 1000),
            "first_marker": FIRST, "final_marker": FINAL,
            "first_png_sha256": first, "final_png_sha256": final,
            "evidence": evidence.relative_to(ROOT).as_posix(),
            "visual_verification": "pending",
        }
        (evidence / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
