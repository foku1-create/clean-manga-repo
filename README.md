# Clean manga extension list

A copy of the [Keiyoushi](https://github.com/keiyoushi/extensions) extension list for Tachimanga (and Mihon)
with every adult extension taken out.

Keiyoushi labels each extension **safe**, **mixed** (normal and adult titles on the same site) or **NSFW**.
Only **safe** ones are kept. The list refreshes itself every hour, so updates and new safe extensions keep arriving.

## Add it to Tachimanga

1. Browse → Extensions → settings → Extension repos
2. Remove the Keiyoushi repo
3. Add this URL:

```
https://github.com/foku1-create/clean-manga-repo/raw/main/index.pb
```

4. Uninstall any adult extensions you already had installed (they stay on the phone until you remove them).

## Files

- `filter.py` builds the list. `allow.txt` keeps a chosen "mixed" extension, `block.txt` removes a chosen "safe" one.
- `removed.txt` lists everything that was taken out.
- Downloads and icons still come straight from Keiyoushi; nothing is re-hosted.
