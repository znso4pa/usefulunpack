# UUT byte transforms and reversible archive wrappers

UUT can automate supported local primitives. `uu tr` implements XOR, byte addition/subtraction, bit rotation, bitwise NOT and an optional leading `skip`. Use UUT to sequence those operations with `uu l`, `uu x`, `uu c`, `uu cmp` or `uu hash` and keep each output in staging.

## Check the byte round trip

Run this example against a small fixture with fresh output names. Supply the source, transformed copy and restored copy as three arguments. `0xAA` is an example value. Use the pipeline confirmed for the actual file when working on a project.

```uut
uu tr "$1" "xor 0xAA" -o "$2"
if $? != 0 then return 1
uu tr "$2" "xor 0xAA" -o "$3"
if $? != 0 then return 1
uu cmp "$1" "$3"
```

## Wrap a new archive

For an agreed outer wrapper, `uu c "stage" "output.ypf" -f ypf --post "xor 0xAA"` creates a native YPF and transforms the complete file. `--post` is for a single output and requires a reversible pipeline. The app applies the inverse internally to check the result. To verify the YPF through its native reader, run `uu tr "output.ypf" "xor 0xAA" -o "verified.ypf"`, then `uu l "verified.ypf"`. This gives the checker the filename extension it uses to select the YPF reader. Use the XP3 writer's `-e` for native XP3 content encryption.

In the in-app Agent, `RUN_UU` and `RUN_UUT` are available under Full local permission and go through the app's confirmations. In local MCP, validate the script with `VALIDATE_UUT`, submit it via `RUN_UUT` and check `TASK_STATUS`. A known reversible byte pipeline covers only the previously identified transform. New per-entry logic belongs in a compatible codec when it cannot be expressed by the exposed commands.
