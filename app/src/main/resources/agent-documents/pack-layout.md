# Archive layout and engine-compatible output names

1. Before packing, record whether the target expects contents at the archive root or under a containing folder. Compare original and staging entry paths, including case and separators.
2. Use a separate output directory for a format whose basename matters, such as RGSS or LPK. Match the original variant and naming rules.
3. List the finished archive and verify that intended paths appear exactly once. Re-extract a sample and test the game or downstream consumer separately from the container checks.
