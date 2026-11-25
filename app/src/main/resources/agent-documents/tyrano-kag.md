# TyranoScript and KAG scenarios

## Locate scenarios

- Use engine files and the directory layout to distinguish TyranoScript from KiriKiri/KAG. Both can use `.ks`. An XP3 index can show the available scenarios. Check whether a `.tjs` file is readable script before treating it as editable text.
- Sample dialogue and choice text alongside the surrounding tags. Scenario labels beginning with `*` may be referenced by jumps. Check `storage` and `target` arguments when verifying that a route still reaches the intended scene.

## Preserve scenario behavior

- Keep labels, filenames, interpolation, macro arguments and expressions. Preserve wait and page-break tags except when the task explicitly changes pacing. Use the matching engine's tag reference for attributes that display visible text.
- Preserve the source encoding and BOM when writing. Test one translated passage and the next branch. Confirm the engine's archive requirements before proposing a replacement XP3 or changing original files.

## Upstream references

- [TyranoScript tag reference](https://tyrano.jp/tag/)
- [KAG3 tag reference](https://krkrz.github.io/krkr2doc/kag3doc/contents/Tags.html) and [KAG3 text display](https://krkrz.github.io/krkr2doc/kag3doc/contents/Letter.html)
