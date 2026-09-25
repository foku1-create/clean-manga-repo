"""Build the clean extension list for Tachimanga (and Mihon).

Every Keiyoushi extension labelled "safe" or "mixed" is kept, with the clean-manga guard patched in:
manga tagged hentai, ecchi or anything else in block-tags.txt show up black and cannot be opened.
Extensions labelled NSFW (adult sites) stay out, unless named in allow.txt.

The patched jar and APK of each extension are signed with our key and uploaded as release files
(tags files-0 .. files-7). index.pb points the app at them.

Format: https://github.com/mihonapp/mihon/blob/main/data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt
"""

import argparse
import base64
import concurrent.futures
import gzip
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zlib
from pathlib import Path

UPSTREAM = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
REPO = "foku1-create/clean-manga-repo"
OWN_URL = f"https://github.com/{REPO}/raw/main/index.pb"
WEBSITE = f"https://github.com/{REPO}"
STORE_NAME = "Keiyoushi Clean"
SHARDS = 8
SAFE, MIXED, NSFW = 1, 2, 3
LABELS = {SAFE: "safe", MIXED: "mixed", NSFW: "adult"}

HERE = Path(__file__).parent
BUILD = HERE / "build"

MAVEN = "https://repo1.maven.org/maven2/"
GOOGLE = "https://dl.google.com/android/maven2/"
LIBS = {
    # tools
    "asm": (MAVEN + "org/ow2/asm/asm/9.8/asm-9.8.jar", "876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051"),
    "asm-tree": (MAVEN + "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar", "14b7880cb7c85eed101e2710432fc3ffb83275532a6a894dc4c4095d49ad59f1"),
    "r8": (GOOGLE + "com/android/tools/r8/8.13.24/r8-8.13.24.jar", "9323b4b8f27bd855f299cf6b870eef4ec879b6b8999718a20aba22937efd5a70"),
    "apksig": (GOOGLE + "com/android/tools/build/apksig/8.13.2/apksig-8.13.2.jar", "c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89"),
    # only to compile the guard; the app provides these at run time
    "kotlin-stdlib": (MAVEN + "org/jetbrains/kotlin/kotlin-stdlib/2.1.20/kotlin-stdlib-2.1.20.jar", "1bcc74e8ce84e2c25eaafde10f1248349cce3062b6e36978cbeec610db1e930a"),
    "rxjava": (MAVEN + "io/reactivex/rxjava/1.3.8/rxjava-1.3.8.jar", "387df880f226b01cea4b1026d96d34e1da27d5801562742cfce0413c21ef7690"),
    "okhttp": (MAVEN + "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar", "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0"),
    "okio": (MAVEN + "com/squareup/okio/okio-jvm/3.9.0/okio-jvm-3.9.0.jar", "ddc386ff14bd25d5c934167196eaf45b18de4f28e1c55a4db37ae594cbfd37e4"),
}
TOOL_LIBS = ["asm", "asm-tree", "r8", "apksig"]
GUARD_LIBS = ["kotlin-stdlib", "rxjava", "okhttp", "okio"]
GUARD_INPUTS = ["block-tags.txt", "blocked.png", "tools/Patcher.java", "tools/ApkPatcher.java", "tools/gen_tags.py"]


# ---- protobuf ----

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


def varint_field(number, value):
    return write_varint(number << 3) + write_varint(value)


def extensions(index):
    """The extension entries of an index, each as a dict with the raw fields kept."""
    out = []
    for number, value, _ in fields(index):
        if number != 101:
            continue
        for inner, entry, _ in fields(value):
            if inner != 1:
                raise ValueError("unexpected field inside the extension list")
            info = {"raw": entry, "sources": []}
            for n, v, whole in fields(entry):
                if n == 1:
                    info["name"] = v.decode()
                elif n == 2:
                    info["pkg"] = v.decode()
                elif n == 3:
                    info["resources"] = {rn: rv.decode() for rn, rv, _ in fields(v)}
                elif n == 4:
                    info["lib"] = v.decode()
                elif n == 5:
                    info["code"] = v
                elif n == 7:
                    info["warning"] = v
                elif n == 8:
                    info["sources"].append(whole)
            out.append(info)
    return out


def rebuild_entry(info, apk_url, jar_url, code):
    """The upstream entry with our download links and version code; everything else unchanged."""
    out = bytearray()
    for n, v, whole in fields(info["raw"]):
        if n == 3:
            res = info["resources"]
            payload = length_field(1, apk_url.encode()) + length_field(2, res.get(2, "").encode()) + length_field(501, jar_url.encode())
            out += length_field(3, payload)
        elif n == 5:
            out += varint_field(5, code)
        else:
            out += whole
    return bytes(out)


def build_index(upstream, entries, fingerprint):
    top = []
    for number, value, whole in fields(upstream):
        if number == 1:
            top.append(length_field(1, STORE_NAME.encode()))
        elif number == 3:
            top.append(length_field(3, fingerprint.encode()))
        elif number == 4:
            top.append(length_field(4, length_field(1, WEBSITE.encode())))
        elif number == 101:
            top.append(length_field(101, b"".join(length_field(1, e) for e in entries)))
        else:
            top.append(whole)
    return b"".join(top)


# ---- helpers ----

def log(*a):
    print(*a, flush=True)


def fetch(url, dest=None, sha256=None, tries=3):
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "clean-manga-repo"})
            data = urllib.request.urlopen(req, timeout=120).read()
            if sha256 and hashlib.sha256(data).hexdigest() != sha256:
                raise ValueError(f"checksum mismatch for {url}")
            if dest:
                Path(dest).write_bytes(data)
            return data
        except Exception:
            if attempt == tries - 1:
                raise


def read_names(filename):
    path = HERE / filename
    if not path.exists():
        return set()
    lines = path.read_text(encoding="utf-8").splitlines()
    return {line.strip().lower() for line in lines if line.strip() and not line.startswith("#")}


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if exe.exists():
            return str(exe)
    return name


def gh(*args, check=True):
    r = subprocess.run(["gh", *args], capture_output=True, text=True, encoding="utf-8")
    if check and r.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:3])} failed: {r.stderr.strip()}")
    return r.stdout


# ---- guard version ----

def guard_hash():
    h = hashlib.sha256()
    files = sorted((HERE / "guard").rglob("*.java")) + [HERE / f for f in GUARD_INPUTS]
    for f in files:
        h.update(f.relative_to(HERE).as_posix().encode())
        h.update(f.read_bytes().replace(b"\r\n", b"\n"))
    return h.hexdigest()[:16]


def guard_bump():
    """Version-code bump: grows by one whenever the guard changes, so installed extensions update."""
    path = HERE / "guard.json"
    state = json.loads(path.read_text()) if path.exists() else {"hash": "", "bump": 0}
    current = guard_hash()
    if state["hash"] != current:
        state = {"hash": current, "bump": state["bump"] + 1}
        if state["bump"] > 99:
            sys.exit("guard bump passed 99; the version code scheme needs a rethink")
        path.write_text(json.dumps(state, indent=2) + "\n")
    return state["bump"]


# ---- tools ----

def compile_tools():
    libs = BUILD / "libs"
    libs.mkdir(parents=True, exist_ok=True)
    paths = {}
    for name, (url, sha) in LIBS.items():
        dest = libs / url.rsplit("/", 1)[1]
        if not dest.exists() or hashlib.sha256(dest.read_bytes()).hexdigest() != sha:
            fetch(url, dest, sha)
        paths[name] = str(dest)

    sep = os.pathsep
    gen = BUILD / "gen" / "cleanmanga" / "guard"
    gen.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(HERE / "tools" / "gen_tags.py"), str(HERE / "block-tags.txt"), str(HERE / "blocked.png"), str(gen / "Tags.java")], check=True)

    for d in ("stub-classes", "guard-classes", "tools"):
        shutil.rmtree(BUILD / d, ignore_errors=True)
        (BUILD / d).mkdir(parents=True)
    javac = java_tool("javac")
    stubs = [str(p) for p in (HERE / "guard" / "stubs").rglob("*.java")]
    subprocess.run([javac, "--release", "8", "-nowarn", "-d", str(BUILD / "stub-classes"), *stubs], check=True)
    guard_cp = sep.join([str(BUILD / "stub-classes")] + [paths[n] for n in GUARD_LIBS])
    guard_src = [str(p) for p in (HERE / "guard" / "src").rglob("*.java")] + [str(gen / "Tags.java")]
    subprocess.run([javac, "--release", "8", "-Xlint:-options", "-nowarn", "-cp", guard_cp, "-d", str(BUILD / "guard-classes"), *guard_src], check=True)
    tools_cp = sep.join(paths[n] for n in TOOL_LIBS)
    subprocess.run([javac, "-nowarn", "-cp", tools_cp, "-d", str(BUILD / "tools"),
                    str(HERE / "tools" / "Patcher.java"), str(HERE / "tools" / "ApkPatcher.java")], check=True)
    return sep.join([str(BUILD / "tools"), tools_cp])


def fingerprint(keystore, alias):
    out = subprocess.run([java_tool("keytool"), "-exportcert", "-rfc", "-keystore", str(keystore), "-alias", alias,
                          "-storepass:env", "CLEAN_KEY_PASSWORD"], capture_output=True, text=True, check=True).stdout
    b64 = "".join(line for line in out.splitlines() if "-----" not in line)
    return hashlib.sha256(base64.b64decode(b64)).hexdigest()


# ---- release files ----

def shard(pkg):
    return f"files-{zlib.crc32(pkg.encode()) % SHARDS}"


def file_url(tag, name):
    return f"https://github.com/{REPO}/releases/download/{tag}/{name}"


def list_release_files():
    """name -> (tag, asset id) for every file in our release shards; creates missing shards."""
    existing = set(gh("api", f"repos/{REPO}/releases", "--paginate", "--jq", ".[].tag_name").split())
    files = {}
    for i in range(SHARDS):
        tag = f"files-{i}"
        if tag not in existing:
            gh("release", "create", tag, "--repo", REPO, "--title", tag, "--notes", "Patched extension files. Managed by build.py; do not edit.", "--latest=false")
            continue
        rel = json.loads(gh("api", f"repos/{REPO}/releases/tags/{tag}"))
        page = 1
        while True:
            assets = json.loads(gh("api", f"repos/{REPO}/releases/{rel['id']}/assets?per_page=100&page={page}"))
            for a in assets:
                files[a["name"]] = (tag, a["id"])
            if len(assets) < 100:
                break
            page += 1
    return files


def upload(tag, paths):
    for i in range(0, len(paths), 40):
        gh("release", "upload", tag, "--repo", REPO, "--clobber", *[str(p) for p in paths[i:i + 40]])


# ---- main ----

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keystore", required=True)
    ap.add_argument("--alias", default="clean")
    ap.add_argument("--max-new", type=int, default=300, help="extensions to patch and upload per run")
    ap.add_argument("--local", help="write files to this folder instead of uploading (for testing)")
    ap.add_argument("--local-url", help="base url the local folder is served at")
    ap.add_argument("--only", help="comma separated package names, for testing")
    ap.add_argument("--out", help="folder for index.pb, repo.json and removed.txt (default: this repo)")
    args = ap.parse_args()
    if not os.environ.get("CLEAN_KEY_PASSWORD"):
        sys.exit("CLEAN_KEY_PASSWORD is not set")

    bump = guard_bump()
    log(f"guard bump {bump}")
    classpath = compile_tools()
    fp = fingerprint(args.keystore, args.alias)
    log(f"signing key {fp}")

    upstream = gzip.decompress(fetch(UPSTREAM))
    exts = extensions(upstream)
    if len(exts) < 500:
        sys.exit(f"only {len(exts)} extensions upstream, format probably changed")
    allow, block = read_names("allow.txt"), read_names("block.txt")
    only = {p.strip() for p in args.only.split(",")} if args.only else None

    out_dir = Path(args.out) if args.out else HERE
    out_dir.mkdir(parents=True, exist_ok=True)
    old_index = out_dir / "index.pb"
    old = {}
    if old_index.exists():
        for e in extensions(gzip.decompress(old_index.read_bytes())):
            old[e["pkg"]] = e

    wanted, removed = [], []
    for e in exts:
        ids = {e["name"].lower(), e["pkg"].lower()}
        label = e.get("warning")
        if only is not None and e["pkg"] not in only:
            continue
        if ids & block:
            removed.append((e, "block.txt"))
        elif label in (SAFE, MIXED) or ids & allow:
            wanted.append(e)
        else:
            removed.append((e, LABELS.get(label, "unlabelled")))

    if args.local:
        local = Path(args.local)
        local.mkdir(parents=True, exist_ok=True)
        present = {p.name: ("local", None) for p in local.iterdir()}
    else:
        present = list_release_files()

    def names(e):
        res = e["resources"]
        jar = res.get(501, "").rsplit("/", 1)[-1]
        apk = res.get(1, "").rsplit("/", 1)[-1]
        if not jar.endswith(".jar") or not apk.endswith(".apk"):
            return None
        return jar[:-4] + f".g{bump}.jar", apk[:-4] + f".g{bump}.apk"

    todo, ready = [], {}
    for e in wanted:
        n = names(e)
        if n is None:
            removed.append((e, "no jar upstream"))
            continue
        if n[0] in present and n[1] in present:
            ready[e["pkg"]] = n
        else:
            todo.append((e, n))
    log(f"{len(wanted)} wanted, {len(ready)} already built, {len(todo)} to build")
    todo = todo[:args.max_new]

    unguardable = {}
    if todo:
        work = BUILD / "work"
        shutil.rmtree(work, ignore_errors=True)
        (work / "up").mkdir(parents=True)
        (work / "out").mkdir(parents=True)

        def download(item):
            e, (jar, apk) = item
            fetch(e["resources"][501], work / "up" / jar)
            fetch(e["resources"][1], work / "up" / apk)

        failed = set()
        with concurrent.futures.ThreadPoolExecutor(16) as pool:
            for item, fut in [(i, pool.submit(download, i)) for i in todo]:
                try:
                    fut.result()
                except Exception as ex:
                    failed.add(item[0]["pkg"])
                    log(f"download failed {item[0]['name']}: {ex}")

        jobs = []
        for e, (jar, apk) in todo:
            if e["pkg"] in failed:
                continue
            mixed = "false" if e.get("warning") == SAFE else "true"
            jobs.append("\t".join([str(work / "up" / jar), str(work / "out" / jar), e["lib"], mixed,
                                   str(work / "up" / apk), str(work / "out" / apk)]))
        (work / "jobs.tsv").write_text("\n".join(jobs) + "\n", encoding="utf-8")
        r = subprocess.run([java_tool("java"), "-Xmx2g", "-cp", classpath, "Patcher", "--batch", str(work / "jobs.tsv"),
                            str(BUILD / "guard-classes"), str(bump), args.keystore, args.alias],
                           capture_output=True, text=True, encoding="utf-8")
        if r.returncode != 0:
            sys.exit(f"patcher crashed:\n{r.stderr}")
        results = {}
        for line in r.stdout.splitlines():
            status, out, detail = (line.split("\t") + ["", ""])[:3]
            results[Path(out).name] = (status, detail)

        built = {}
        for e, (jar, apk) in todo:
            status, detail = results.get(jar, ("error", "not processed"))
            if status == "ok" and (work / "out" / apk).exists():
                built[e["pkg"]] = (jar, apk)
            elif status == "unguardable":
                unguardable[e["pkg"]] = detail
                log(f"unguardable {e['name']}: {detail}")
            else:
                log(f"failed {e['name']}: {status} {detail}")

        by_tag = {}
        for pkg, (jar, apk) in built.items():
            if args.local:
                shutil.copy(work / "out" / jar, Path(args.local) / jar)
                shutil.copy(work / "out" / apk, Path(args.local) / apk)
            else:
                by_tag.setdefault(shard(pkg), []).extend([work / "out" / jar, work / "out" / apk])
        for tag, paths in by_tag.items():
            upload(tag, paths)
        ready.update(built)
        log(f"built {len(built)}")

    def url(pkg, name):
        if args.local:
            return f"{args.local_url.rstrip('/')}/{name}"
        return file_url(shard(pkg), name)

    entries, published = [], []
    for e in wanted:
        pkg = e["pkg"]
        if pkg in ready:
            jar, apk = ready[pkg]
            code = int(e["code"]) * 100 + bump
            entries.append(rebuild_entry(e, url(pkg, apk), url(pkg, jar), code))
            published.append(e)
        elif pkg in unguardable:
            removed.append((e, "could not add the guard: " + unguardable[pkg]))
        elif pkg in old and not args.local and old[pkg]["resources"].get(501, "").rsplit("/", 1)[-1] in present:
            # not rebuilt yet this run: keep last run's guarded version
            entries.append(old[pkg]["raw"])
            published.append(old[pkg])

    # Refuse to publish anything odd; the last good index then stays online.
    if only is None and len(entries) < 300:
        sys.exit(f"only {len(entries)} extensions ready, not publishing")
    for e in published:
        if e.get("warning") == NSFW and not ({e["name"].lower(), e["pkg"].lower()} & allow):
            sys.exit(f"adult extension slipped through: {e['name']}")

    index = build_index(upstream, entries, fp)
    check = extensions(index)
    if len(check) != len(entries):
        sys.exit("the new index does not read back the same")
    own = args.local_url if args.local else f"https://github.com/{REPO}/releases/download/"
    for e in check:
        res = e["resources"]
        if not res.get(501, "").startswith(own) or not res.get(1, "").startswith(own):
            sys.exit(f"{e['name']} would download unguarded files")

    (out_dir / "index.pb").write_bytes(gzip.compress(index, mtime=0))
    repo = {"index_v2": OWN_URL, "meta": {"name": STORE_NAME, "website": WEBSITE, "signingKeyFingerprint": fp}}
    (out_dir / "repo.json").write_text(json.dumps(repo, indent=2) + "\n", encoding="utf-8")
    lines = sorted(f"{e['name']} ({e['pkg'].rsplit('.', 1)[-1]}): {why}" for e, why in removed)
    (out_dir / "removed.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"published {len(entries)}, removed {len(removed)}")

    # Tidy files no index points at any more (keeps the previous index's files one more run).
    if not args.local:
        keep = set()
        for e in check + list(old.values()):
            for u in (e["resources"].get(1, ""), e["resources"].get(501, "")):
                keep.add(u.rsplit("/", 1)[-1])
        stale = [(name, tag, aid) for name, (tag, aid) in present.items() if name not in keep]
        for name, tag, aid in stale[:200]:
            gh("api", "-X", "DELETE", f"repos/{REPO}/releases/assets/{aid}", check=False)
        if stale:
            log(f"deleted {min(len(stale), 200)} old files")


if __name__ == "__main__":
    main()
