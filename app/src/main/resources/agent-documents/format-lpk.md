# Lucifen LPK archives (.lpk)

1. Keep the intended original basename when copying to a new directory, since the format uses the filename in its key handling. Index failures can be caused by missing context or a changed name.
2. List and sample the content using the supported reader. If an archive uses a per-archive key, obtain it through the local supported workflow.
3. Use the LPK packer with the intended basename in a separate output folder. Reopen with the same name and key context. Avoid collision suffixes on names intended for the engine.
