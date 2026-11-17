"""Loopback provider for device integration tests; contains synthetic data only."""
import argparse
import base64
import json
import hashlib
import io
import struct
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from email.parser import BytesParser
from email.policy import default

MARKDOWN_REPLY = """# MD_HEADING

**MD_BOLD** and *MD_ITALIC* with `MD_INLINE`.

- MD_ITEM_ONE
- MD_ITEM_TWO

> MD_QUOTE

```python
print("MD_CODE_COPY")
```

| Item | Value |
| --- | --- |
| MD_TABLE | 42 |

[MD_LINK](https://example.com)

MD_END
"""


def fixture_png(edited=False):
    """A deterministic checkerboard, not a generated model image."""
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
    pixels = b"".join(b"\0" + b"".join(
        bytes(((70, 90, 220) if (x // 16 + y // 16) % 2 else (240, 220, 40)) if edited
              else ((20, 160, 170) if (x // 16 + y // 16) % 2 else (250, 150, 40)))
        for x in range(128)) for y in range(96))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 128, 96, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.endswith("/models"):
            response = json.dumps({"data": [{"id": name} for name in ["FIXTURE_CHAT", "FIXTURE_THINKING", "FIXTURE_IMAGE"]]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)
            return
        if self.path != "/fixture.png":
            self.send_error(404)
            return
        response = fixture_png()
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        try:
            self.wfile.write(response)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        image_inputs = []
        if self.headers.get("Content-Type", "").startswith("multipart/"):
            message = BytesParser(policy=default).parsebytes(("Content-Type: " + self.headers["Content-Type"] + "\r\nMIME-Version: 1.0\r\n\r\n").encode() + raw)
            body = {}
            from PIL import Image
            for part in message.iter_parts():
                name = part.get_param("name", header="content-disposition")
                value = part.get_payload(decode=True)
                if part.get_filename():
                    image = Image.open(io.BytesIO(value))
                    image_inputs.append({"name": name, "mime": part.get_content_type(), "sha256": hashlib.sha256(value).hexdigest(),
                                         "width": image.width, "height": image.height, "bytes": len(value)})
                else:
                    body[name] = value.decode("utf-8")
        else:
            body = json.loads(raw)
        recorded_body = json.loads(json.dumps(body))
        screens = []
        phone_tools = any(tool.get("function", {}).get("name") == "open_app" for tool in body.get("tools", []))
        delegate_tools = any(tool.get("function", {}).get("name") == "generate_image" or tool.get("function", {}).get("name") == "control_phone" for tool in body.get("tools", []))
        if phone_tools or delegate_tools or body.get("model") == "FIXTURE_VISION":
            from PIL import Image
            for message in recorded_body.get("messages", []):
                if not isinstance(message.get("content"), list):
                    continue
                for part in message["content"]:
                    if part.get("type") != "image_url":
                        continue
                    content = base64.b64decode(part["image_url"]["url"].split(",", 1)[1])
                    digest = hashlib.sha256(content).hexdigest()
                    image = Image.open(io.BytesIO(content))
                    screens.append({"sha256": digest, "bytes": len(content), "width": image.width, "height": image.height})
                    if phone_tools or delegate_tools:
                        self.server.records.parent.joinpath("control-screen-" + digest[:16] + ".jpg").write_bytes(content)
                    part["image_url"]["url"] = "<redacted screenshot sha256=" + digest + ">"
        token = "FIXTURE_TOKEN"
        if self.path.startswith("/planner/"):
            token = body.get("model", "") + "_TOKEN"
        if self.path.startswith("/image/"):
            token = "FIXTURE_IMAGE_TOKEN"
        if self.path.startswith("/control/"):
            token = "FIXTURE_CONTROL_TOKEN"
        record = {"path": self.path, "body": recorded_body, "screens": screens, "image_inputs": image_inputs,
                  "fixture_token_matches": self.headers.get("Authorization") == "Bearer " + token}
        with self.server.records.open("a", encoding="utf-8") as output:
            output.write(json.dumps(record, ensure_ascii=False) + "\n")
        if self.path.startswith("/failure/"):
            self.send_error(401, "fixture auth failure")
            return
        if body.get("model") == "FIXTURE_SYSTEM":
            if any(message.get("role") == "tool" for message in body["messages"]):
                result = {"choices": [{"message": {"role": "assistant", "content": "SYSTEM_TOOL_FIXTURE_OK"}}]}
            else:
                result = {"choices": [{"message": {"role": "assistant", "tool_calls": [
                    {"id": "LAUNCH_CLOCK", "type": "function", "function": {
                        "name": "launch_app", "arguments": json.dumps({"app_name": "时钟"})}}]}}]}
        elif body.get("model") == "FIXTURE_VISION":
            result = {"choices": [{"message": {"role": "assistant", "content": "VISION_FIXTURE_OK"}}]}
        elif body.get("messages", [{}])[-1].get("content") == "VISION_ENGINE_SEED":
            result = {"choices": [{"message": {"role": "assistant", "content": "VISION_SEED_OK"}}]}
        elif delegate_tools:
            messages = body["messages"]
            user_index = max(index for index, message in enumerate(messages) if message.get("role") == "user")
            text = next((message["content"] for message in reversed(messages) if message.get("role") == "user"), "")
            if isinstance(text, list):
                text = text[0]["text"]
            control = "AUTOROUTE_CONTROL" in text
            function = "control_phone" if control else "generate_image"
            if any(message.get("role") == "tool" and message.get("tool_call_id", "").startswith("AUTO_") for message in messages[user_index+1:]):
                result = {"choices": [{"message": {"role": "assistant", "content": "AUTOROUTE_CONTROL_OK" if control else "AUTOROUTE_IMAGE_OK"}}]}
            else:
                arguments = {"task": text} if control else {"prompt": text}
                if "AUTOROUTE_PREVIOUS" in text:
                    arguments["image"] = "previous"
                result = {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
                    {"id": "AUTO_" + str(user_index), "type": "function", "function": {"name": function, "arguments": json.dumps(arguments)}}]}}]}
        elif phone_tools:
            calls = sum(1 for message in body["messages"] if message.get("role") == "tool")
            if calls == 0 and any("CONTROL_CANCEL" in str(message.get("content", "")) for message in body["messages"]):
                time.sleep(8)
            action = [("open_app", {"package": "com.android.settings"}), ("screenshot", {}), ("press_key", {"key": "home"})]
            goal = next((message["content"][0]["text"] for message in body["messages"]
                         if message.get("role") == "user" and isinstance(message.get("content"), list)), "")
            if goal.startswith("CONTROL_GESTURE "):
                points = json.loads(goal.split(" ", 1)[1])
                action = [("tap", {"x": points["tap"][0], "y": points["tap"][1]}),
                          ("tap", {"x": points["input"][0], "y": points["input"][1]}),
                          ("type_text", {"text": "控制测试XASWITCH"}), ("press_key", {"key": "back"}),
                          ("swipe", {"x": points["swipe"][0], "y": points["swipe"][1],
                                     "x2": points["swipe"][2], "y2": points["swipe"][3], "duration_ms": 400}),
                          ("screenshot", {})]
            if calls < len(action):
                name, arguments = action[calls]
                result = {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
                    {"id": "CONTROL_" + str(calls), "type": "function", "function": {
                        "name": name, "arguments": json.dumps(arguments)}}]}}]}
            else:
                result = {"choices": [{"message": {"role": "assistant", "content": "CONTROL_FIXTURE_OK"}}]}
        elif self.path.endswith("/images/generations") or self.path.endswith("/images/edits"):
            image = {"b64_json": base64.b64encode(fixture_png(self.path.endswith("/images/edits"))).decode()}
            if "URL_RESULT" in body.get("prompt", ""):
                image = {"url": f"http://127.0.0.1:{self.server.server_port}/fixture.png"}
            result = {"data": [image]}
        else:
            user = body.get("messages", [{}])[-1].get("content", "")
            if "DELAY_RESULT" in user:
                time.sleep(3)
            reply = MARKDOWN_REPLY if "MARKDOWN_FIXTURE" in user else "STALE_FIRST_REPLY" if "SUPERSEDE_FIRST" in user else "FRESH_SECOND_REPLY" if "SUPERSEDE_SECOND" in user else "XASWITCH_FIXTURE_OK"
            result = {"choices": [{"message": {"role": "assistant", "content": reply}}]}
        response = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        try:
            self.wfile.write(response)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--records", default="evidence/fixture-requests.jsonl")
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.records = Path(args.records)
    server.records.parent.mkdir(parents=True, exist_ok=True)
    print(f"fixture listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()
