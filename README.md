# Clean manga extension list

A copy of the [Keiyoushi](https://github.com/keiyoushi/extensions) extension list for Tachimanga (and Mihon)
where every extension carries a small guard: **manga tagged hentai, ecchi or adult show up black and cannot be opened.**
The sites themselves stay normal.

- Sites Keiyoushi labels **safe** or **mixed** (normal and adult titles on the same site) are kept, with the guard inside.
- Sites labelled **NSFW** (adult sites) stay out completely.
- The list refreshes itself every hour, so updates and new extensions keep arriving.

## What the guard does

- In lists and searches, a blocked title shows as **Blocked** with a black cover.
- A cover only appears once that title's tags have been checked. The first time you scroll a list, covers take a few seconds.
- Opening a blocked title shows **Blocked** and no chapters. Chapters you had before cannot be opened.
- Blocked titles are remembered, also after the app restarts.

The tags that block are in [`block-tags.txt`](block-tags.txt) (hentai, ecchi, smut, adult, mature, erotica, the same words in other languages, and MangaDex's "suggestive" rating).
Change that file and every extension is rebuilt within the hour.

## Add it to Tachimanga

1. Browse → Extensions → settings → Extension repos
2. Remove the Keiyoushi repo (and this one, if you added it before)
3. Add this URL:

```
https://github.com/foku1-create/clean-manga-repo/raw/main/index.pb
```

4. Uninstall the extensions you already have, then install them again from this list.
   Only extensions installed from this list have the guard.

## Files

- `build.py` builds everything: picks the extensions, patches the guard in, signs, uploads, writes `index.pb`.
- `guard/` is the guard itself; `tools/Patcher.java` puts it in front of the extension; `tools/ApkPatcher.java` makes the Android version.
- `allow.txt` keeps a chosen NSFW-labelled extension (with the guard), `block.txt` removes a chosen extension.
- `removed.txt` lists everything that was left out, and why.
- Patched files are signed with this repo's own key and stored as release files (`files-0` … `files-7`).
