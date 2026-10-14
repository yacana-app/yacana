"""Checks a translation of Yacana's strings against the English ones.

Usage: python check_translation.py <repo> <lang>    (lang: fr, it, pt, de, ca)

For both resource files (the shared UI's Compose resources and the Android app's), it checks that
the XML parses, that every English key is there and no other, that each string keeps exactly the
English placeholders (%1$s, %2$d, %%...), that lists have as many items, that plurals have at least "one" and "other" with no
placeholder English lacks, and, in the Android file, that apostrophes are escaped as \\'.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

repo, lang = sys.argv[1], sys.argv[2]
FILES = [
    ("shared/ui/src/commonMain/composeResources/values/strings.xml", f"shared/ui/src/commonMain/composeResources/values-{lang}/strings.xml", False),
    ("app/src/main/res/values/strings.xml", f"app/src/main/res/values-{lang}/strings.xml", True),
]
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdf]|%%")


def text_of(element):
    return "".join(element.itertext())


def load(path):
    root = ET.parse(path).getroot()
    strings, plurals, untranslatable = {}, {}, set()
    for child in root:
        name = child.get("name")
        if child.tag == "string-array":
            # Each item of a list, as the weekdays, is checked as a string of its own.
            for index, item in enumerate(child.findall("item")):
                strings[f"{name}[{index}]"] = text_of(item)
        elif child.tag == "string":
            if child.get("translatable") == "false":
                untranslatable.add(name)
            else:
                strings[name] = text_of(child)
        elif child.tag == "plurals":
            plurals[name] = {item.get("quantity"): text_of(item) for item in child.findall("item")}
    return strings, plurals, untranslatable


problems = []
for english_path, target_path, android in FILES:
    try:
        en_strings, en_plurals, skip = load(f"{repo}/{english_path}")
        strings, plurals, _ = load(f"{repo}/{target_path}")
    except (ET.ParseError, FileNotFoundError) as e:
        problems.append(f"{target_path}: {e}")
        continue
    for key in sorted(set(en_strings) - set(strings)):
        problems.append(f"{target_path}: missing string {key}")
    for key in sorted(set(strings) - set(en_strings)):
        problems.append(f"{target_path}: string {key} is not in English" + (" (untranslatable: leave it out)" if key in skip else ""))
    for key in sorted(set(en_plurals) - set(plurals)):
        problems.append(f"{target_path}: missing plurals {key}")
    for key, text in strings.items():
        if key in en_strings and Counter(PLACEHOLDER.findall(text)) != Counter(PLACEHOLDER.findall(en_strings[key])):
            problems.append(f"{target_path}: {key} placeholders {PLACEHOLDER.findall(text)} != English {PLACEHOLDER.findall(en_strings[key])}")
        if not text.strip():
            problems.append(f"{target_path}: {key} is empty")
    for key, items in plurals.items():
        if key not in en_plurals:
            problems.append(f"{target_path}: plurals {key} is not in English")
            continue
        for quantity in ("one", "other"):
            if quantity not in items:
                problems.append(f"{target_path}: plurals {key} lacks {quantity}")
        allowed = set(PLACEHOLDER.findall(en_plurals[key].get("other", "")))
        for quantity, text in items.items():
            extra = set(PLACEHOLDER.findall(text)) - allowed
            if extra:
                problems.append(f"{target_path}: plurals {key}/{quantity} has {extra} that English lacks")
    if android:
        raw = open(f"{repo}/{target_path}", encoding="utf-8").read()
        for line in raw.splitlines():
            body = re.sub(r"<[^>]+>", "", line)
            if re.search(r"(?<!\\)'", body):
                problems.append(f"{target_path}: unescaped apostrophe in: {line.strip()[:90]}")

print("\n".join(problems) if problems else f"{lang}: OK")
sys.exit(1 if problems else 0)
