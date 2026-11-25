# RPG Maker MV/MZ wrapped PNG (.rpgmvp / .png_)

1. Treat this as one wrapped image. Use the native MV/MZ reader and verify the decoded PNG signature and dimensions. Renaming the file alone will not remove the format header.
2. For an authorized project edit, obtain its key through the game's local configuration or the app's local key entry. A successfully recovered preview alone does not establish that the writer has the needed key.
3. Pack a PNG with the `rpgmvp` key and verify the result with the target project's naming convention. Re-extract and compare the PNG before checking rendering in the game.
