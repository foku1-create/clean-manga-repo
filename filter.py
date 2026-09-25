"""Build a copy of the Keiyoushi extension list with every adult extension removed.

Keiyoushi labels each extension SAFE, MIXED (normal and adult titles on one
site) or NSFW. Only SAFE ones are kept, plus anything named in allow.txt, minus
anything named in block.txt. Extension entries are copied byte for byte, so
download links, icons and Tachimanga's own extra fields stay exactly as
upstream publishes them.

Format: https://github.com/mihonapp/mihon/blob/main/data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt
"""

import gzip
import json
import sys
import urllib.request
from pathlib import Path

UPSTREAM = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
OWN_URL = "https://github.com/foku1-create/clean-manga-repo/raw/main/index.pb"
STORE_NAME = "Keiyoushi Clean"

SAFE, MIXED, NSFW = 1, 2, 3
HERE = Path(__file__).parent


def read_varint(buf, i):
    result = shift = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:
            return result, i


def write_varint(n):
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def fields(buf):
    """Yield (field number, value, raw bytes of the whole field)."""
    i = 0
    while i < len(buf):
        start = i
        key, i = read_varint(buf, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = read_varint(buf, i)
        elif wire == 2:
            size, i = read_varint(buf, i)
            value = buf[i:i + size]
            i += size
        elif wire == 1:
            value = buf[i:i + 8]
            i += 8
        elif wire == 5:
            value = buf[i:i + 4]
            i += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        yield number, value, buf[start:i]


def length_field(number, payload):
    return write_varint(number << 3 | 2) + write_varint(len(payload)) + payload


def read_names(filename):
    path = HERE / filename
    if not path.exists():
        return set()
    lines = path.read_text(encoding="utf-8").splitlines()
    return {line.strip().lower() for line in lines if line.strip() and not line.startswith("#")}


def describe(entry):
    info = {"sources": []}
    for number, value, _ in fields(entry):
        if number == 1:
            info["name"] = value.decode()
        elif number == 2:
            info["pkg"] = value.decode()
        elif number == 7:
            info["warning"] = value
        elif number == 8:
            info["sources"].append(value)
    return info


def build(raw, allow, block):
    top, kept, dropped = [], [], []
    for number, value, whole in fields(raw):
        if number == 1:
            top.append(length_field(1, STORE_NAME.encode()))
        elif number == 101:
            entries = bytearray()
            for inner_number, entry, entry_whole in fields(value):
                if inner_number != 1:
                    raise ValueError("unexpected field inside the extension list")
                info = describe(entry)
                ids = {info["name"].lower(), info["pkg"].lower()}
                wanted = (info.get("warning") == SAFE or ids & allow) and not ids & block
                if wanted:
                    entries += entry_whole
                    kept.append(info)
                else:
                    dropped.append(info)
            top.append(length_field(101, bytes(entries)))
        else:
            top.append(whole)
    return b"".join(top), kept, dropped


def main():
    request = urllib.request.Request(UPSTREAM, headers={"User-Agent": "clean-manga-repo"})
    raw = gzip.decompress(urllib.request.urlopen(request, timeout=60).read())
    allow, block = read_names("allow.txt"), read_names("block.txt")
    clean, kept, dropped = build(raw, allow, block)

    # Refuse to publish anything odd; the last good file then stays online.
    if len(kept) < 100:
        sys.exit(f"only {len(kept)} extensions left, upstream format probably changed")
    leaked = [e["name"] for e in kept if e.get("warning") != SAFE and e["name"].lower() not in allow and e["pkg"].lower() not in allow]
    if leaked:
        sys.exit(f"non-safe extensions slipped through: {leaked}")
    _, recheck, _ = build(clean, allow, block)
    if len(recheck) != len(kept):
        sys.exit("the cleaned file does not read back the same")

    (HERE / "index.pb").write_bytes(gzip.compress(clean, mtime=0))
    signing_key = next(v.decode() for n, v, _ in fields(raw) if n == 3)
    repo = {"index_v2": OWN_URL, "meta": {"name": STORE_NAME, "website": "https://github.com/foku1-create/clean-manga-repo", "signingKeyFingerprint": signing_key}}
    (HERE / "repo.json").write_text(json.dumps(repo, indent=2) + "\n", encoding="utf-8")
    removed = sorted(f"{e['name']} ({e['pkg'].rsplit('.', 1)[-1]})" for e in dropped)
    (HERE / "removed.txt").write_text("\n".join(removed) + "\n", encoding="utf-8")
    print(f"kept {len(kept)}, removed {len(dropped)}")


if __name__ == "__main__":
    main()
