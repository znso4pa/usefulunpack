# RPG Maker VX RGSS archive (.rgss2a)

1. Use the native RGSS reader to list `.rgss2a` entries. Its layout is close to the XP layout, so engine and archive extension determine the correct pack target.
2. Extract into staging with the complete relative paths. Verify file content before moving on to localization or repacking.
3. Use `uu c "stage" "rebuild/Game.rgss2a" -f rgss2a` for VX. Verify the intended basename and test a restored member before checking the game.
