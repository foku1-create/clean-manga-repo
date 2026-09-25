"""Write Tags.java: the tag list from block-tags.txt and the black cover, baked into the guard."""

import json
import sys


def main(tags_file, cover_file, out_file):
    lines = []
    for raw in open(tags_file, encoding="utf-8"):
        line = raw.strip()
        if line and not line.startswith("#"):
            lines.append(line)
    png = open(cover_file, "rb").read()

    src = [
        "package cleanmanga.guard;",
        "",
        "// Generated from block-tags.txt and blocked.png by tools/gen_tags.py. Do not edit.",
        "final class Tags {",
        "    static final String[] LINES = {",
    ]
    src += [f"        {json.dumps(line, ensure_ascii=True)}," for line in lines]
    src += [
        "    };",
        "",
        "    static final byte[] COVER_PNG = {" + ",".join(str(b - 256 if b > 127 else b) for b in png) + "};",
        "",
        "    private Tags() {",
        "    }",
        "}",
        "",
    ]
    with open(out_file, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(src))
    print(f"{len(lines)} tags")


if __name__ == "__main__":
    main(*sys.argv[1:4])
