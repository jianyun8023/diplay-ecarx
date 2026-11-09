#!/usr/bin/env python3
"""Turn a GitHub release body into the plain text that update.json carries.

Installed apps show the notes as plain text, and older ones show them verbatim, so the
manifest must hold no Markdown syntax. Keep in step with UpdateNotes.kt.

Usage: release_notes_plain.py < body.md > notes.txt
"""
import re
import sys

HEADING = re.compile(r"^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$")
LIST_ITEM = re.compile(r"^(\s*)[-*+]\s+")
IMAGE = re.compile(r"!\[([^\]]*)]\([^)]*\)")
LINK = re.compile(r"\[([^\]]+)]\([^)]*\)")
STRONG = re.compile(r"(\*\*|__)(.+?)\1")
EMPHASIS = re.compile(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])")
CODE = re.compile(r"`([^`]*)`")
BLANK_RUN = re.compile(r"\n{3,}")


def plain(markdown: str) -> str:
    lines = []
    for line in markdown.replace("\r\n", "\n").split("\n"):
        heading = HEADING.fullmatch(line)
        text = heading.group(1) if heading else LIST_ITEM.sub(lambda m: m.group(1) + "• ", line, count=1)
        text = IMAGE.sub(r"\1", text)
        text = LINK.sub(r"\1", text)
        text = CODE.sub(r"\1", text)
        text = STRONG.sub(r"\2", text)
        text = EMPHASIS.sub(r"\1", text)
        lines.append(text)
    return BLANK_RUN.sub("\n\n", "\n".join(lines)).strip()


if __name__ == "__main__":
    sys.stdout.write(plain(sys.stdin.read()))
