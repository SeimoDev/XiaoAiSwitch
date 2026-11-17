"""Inventory DEX strings without executing the APK."""
import json
import re
import struct
import sys
import zipfile
from pathlib import Path


def strings(data):
    count, offset = struct.unpack_from("<II", data, 56)
    for index in range(count):
        position = struct.unpack_from("<I", data, offset + 4 * index)[0]
        while data[position] & 128:
            position += 1
        position += 1
        end = data.index(0, position)
        yield data[position:end].decode("utf-8", errors="replace")


if __name__ == "__main__":
    pattern = re.compile(sys.argv[2], re.I)
    with zipfile.ZipFile(sys.argv[1]) as archive:
        result = sorted({value for name in archive.namelist()
                         if re.fullmatch(r"classes\d*\.dex", name)
                         for value in strings(archive.read(name))
                         if pattern.search(value)})
    if len(sys.argv) > 3:
        Path(sys.argv[3]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    else:
        print("\n".join(result))
