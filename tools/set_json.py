#!/usr/bin/env python3
"""set_json.py <file> key=value ... -- set fields in a JSON document, creating it when missing."""
import json
import sys

path = sys.argv[1]
try:
    doc = json.load(open(path, encoding="utf-8"))
except (FileNotFoundError, json.JSONDecodeError):
    doc = {}
for pair in sys.argv[2:]:
    key, _, value = pair.partition("=")
    doc[key] = value
json.dump(doc, open(path, "w", encoding="utf-8"))
