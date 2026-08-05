#!/usr/bin/env python3
"""Extract remaining functions from MainActivity.kt into separate files."""
import re, os

with open('app/src/main/java/com/usefulunpacker/MainActivity.kt', 'r') as f:
    raw = f.read()

lines = raw.split('\n')

def find_fn_line(name):
    """Find the line index of 'private fun name('"""
    for i, line in enumerate(lines):
        if line.strip().startswith(f'private fun {name}('):
            return i
    return None

def find_fn_end(start_line):
    """Find the end of function starting at start_line"""
    indent = None
    for i in range(start_line, len(lines)):
        if lines[i].strip() == '':
            continue
        if indent is None:
            # Get indentation of the first line after the function signature
            if i == start_line:
                indent = len(lines[i]) - len(lines[i].lstrip())
            continue
        # Check if we hit a new function/val at the same indent level
        stripped = lines[i].lstrip()
        cur_indent = len(lines[i]) - len(stripped)
        if cur_indent <= indent and (stripped.startswith('private ') or stripped.startswith('internal ') or stripped.startswith('}') and i+1 < len(lines) and lines[i+1].lstrip().startswith('private ')):
            return i
    return len(lines)

def extract_function(name, new_file, transform=None):
    """Extract a function from MainActivity and write to new_file.
    transform: optional function to transform the extracted lines"""
    start = find_fn_line(name)
    if start is None:
        print(f"  WARNING: {name} not found")
        return None
    end = find_fn_end(start)
    extracted = lines[start:end]
    if transform:
        extracted = transform(extracted)
    with open(new_file, 'a') as f:
        if os.path.getsize(new_file) > 0:
            f.write('\n\n')
        f.write('\n'.join(extracted))
    # Remove from lines
    del lines[start:end]
    print(f"  Extracted {name} ({end-start} lines)")
    return start

def extract_functions(names, new_file, transform=None):
    """Extract multiple functions to one file, in reverse order to preserve indices"""
    for name in reversed(names):
        extract_function(name, new_file, transform)

def strip_private(line):
    """Convert 'private fun ...' to just 'fun ...' and 'private val ...' to 'val ...'"""
    return line.replace('private fun ', 'fun ').replace('private val ', 'val ')
