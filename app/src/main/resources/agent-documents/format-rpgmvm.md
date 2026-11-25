# RPG Maker MV/MZ wrapped M4A (.rpgmvm / .m4a_)

1. Use the MV/MZ native reader for the single M4A asset. Confirm the decoded container and audio playback. Reconstruction without the project key can produce a playable header with different metadata bytes.
2. Preserve the original as the comparison source and obtain the project key locally for verified rewrapping. Use an actual supported `.m4a` file as input.
3. Pack with `rpgmvm`, then check decoded content and target-engine playback. Report the level of verification available when only a reconstructed header was used.
