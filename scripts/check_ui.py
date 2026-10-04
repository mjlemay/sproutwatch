#!/usr/bin/env python3
"""Static sanity check for client-parsed .ui documents: balanced braces/parens, nothing after the
last top-level element closes. A parse failure only shows up in-game (client disconnects with
"Failed to load CustomUI documents"), so run this before every deploy."""
import sys, pathlib

def check(path):
    errors = []
    depth = {'{': 0, '(': 0}
    pairs = {'}': '{', ')': '('}
    in_str = False
    for n, line in enumerate(path.read_text().splitlines(), 1):
        code = line.split('//', 1)[0] if not in_str else line
        for ch in code:
            if ch == '"':
                in_str = not in_str
            elif in_str:
                continue
            elif ch in depth:
                depth[ch] += 1
            elif ch in pairs:
                depth[pairs[ch]] -= 1
                if depth[pairs[ch]] < 0:
                    errors.append(f"{path}:{n}: unmatched '{ch}'")
                    depth[pairs[ch]] = 0
    for k, v in depth.items():
        if v:
            errors.append(f"{path}: {v} unclosed '{k}'")
    return errors

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/Common/UI/Custom')
errs = [e for f in sorted(root.rglob('*.ui')) for e in check(f)]
print('\n'.join(errs) if errs else f"ok: {len(list(root.rglob('*.ui')))} .ui file(s) balanced")
sys.exit(1 if errs else 0)
