# Ren'Py scripts and translation

## Locate the translation source

- Start with the `game` directory. Check `game/tl/<language>/` and loose `.rpy` scripts first. List RPA entries if the relevant files are missing. Extraction exposes bundled files in their stored form. Treat `.rpyc` as compiled material and report whether you found editable source.
- `translate <language> <id>:` blocks handle dialogue. Retain the identifier connecting each translated block to the script. In `translate <language> strings:` blocks, keep `old` strings as lookup keys and put translations in `new` entries. A context marker such as `{#menu}` can distinguish identical source strings.

## Preserve the script

- Keep indentation, character names used as code identifiers, string delimiters, interpolation such as `[name]`, and text tags such as `{i}` and `{/i}`. Move a paired tag only if the translated sentence requires it, while preserving both ends.
- Try one dialogue line, one menu choice and one line containing interpolation. Check the actual language selection and layout in the engine if available. Report separately if only file or archive checks were possible.

## Upstream references

- [Ren'Py translation documentation](https://www.renpy.org/doc/html/translation.html)
- [Ren'Py text tags and interpolation](https://www.renpy.org/doc/html/text.html)
- [Ren'Py build and archive documentation](https://www.renpy.org/doc/html/build.html)
