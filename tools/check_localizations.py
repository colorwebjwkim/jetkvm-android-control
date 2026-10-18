"""Check complete locale keys and compatible Android string format placeholders."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'app/src/main/res'
def strings(folder):
    entries = ET.parse(root / folder / 'strings.xml').getroot().findall('string')
    result = {entry.attrib['name']: ''.join(entry.itertext()) for entry in entries}
    assert len(result) == len(entries), f'Duplicate keys in {folder}'
    return result

base = strings('values')
for folder in ('values-en', 'values-ja'):
    localized = strings(folder)
    assert localized.keys() == base.keys(), f'Missing or extra keys in {folder}'
    for key, text in localized.items():
        assert text.strip(), f'Empty translation: {folder}/{key}'
        assert sorted(re.findall(r'%\d+\$[sd]', text)) == sorted(re.findall(r'%\d+\$[sd]', base[key])), f'Format mismatch: {folder}/{key}'
print(f'PASS: {len(base)} strings complete in Korean, English and Japanese; placeholders match')
