# Clean manga extension list

A copy of the [Keiyoushi](https://github.com/keiyoushi/extensions) extension list for Tachimanga (and Mihon)
where every extension carries a small guard: **manga tagged hentai, ecchi or adult show up black and cannot be opened.**
The sites themselves stay normal.

- Sites Keiyoushi labels **safe** or **mixed** (normal and adult titles on the same site) are kept, with the guard inside.
- Sites labelled **NSFW** (adult sites) stay out completely.
- The list refreshes itself every hour, so updates and new extensions keep arriving.

## What the guard does

- Every list and search is checked **before** the app gets it: each title's tags are looked up first.
  A blocked title arrives as **Blocked** with a black cover, so its real name and cover never reach the app,
  however the app loads or caches covers.
- A list waits at most 12 seconds for these checks. A title that is not checked by then shows as **Checking**
  (black, like a blocked one). Reload the list a moment later and the clean ones appear.
- Every answer is remembered, also after the app restarts, so a list you have seen before opens straight away.
  Changing `block-tags.txt` makes the guard check the clean ones again.
- Opening a blocked title shows **Blocked** and no chapters. Chapters you had before cannot be opened.

The tags that block are in [`block-tags.txt`](block-tags.txt) (hentai, ecchi, smut, adult, mature, erotica, yaoi and yuri including "Boys' Love" and "Girls' Love", the same words in other languages, and MangaDex's "suggestive" rating).
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
5. Do this on **every** device (iPhone, iPad, Mac). Each one keeps its own extensions.
   When the guard improves, the extensions show an update: press **Update all** on each device.

## Files

- `build.py` builds everything: picks the extensions, patches the guard in, signs, uploads, writes `index.pb`.
- `guard/` is the guard itself; `tools/Patcher.java` puts it in front of the extension; `tools/ApkPatcher.java` makes the Android version.
- `allow.txt` keeps a chosen NSFW-labelled extension (with the guard), `block.txt` removes a chosen extension.
- `removed.txt` lists everything that was left out, and why.
- Patched files are signed with this repo's own key and stored as release files (`files-0` … `files-7`).
