# RPG Maker MV/MZ wrapped Ogg (.rpgmvo / .ogg_)

1. Use the one-file MV/MZ reader for a wrapped Ogg asset. Check the `OggS` container and playback after decoding rather than judging by the wrapper extension.
2. Keep the original asset basename and project key context for repacking. Decode and encode paths are separate from audio recoding.
3. With the local project key, pack a verified `.ogg` source with `rpgmvo`. Verify playback after re-extraction and confirm the engine expects the chosen extension.
