#!/usr/bin/env python3
"""Lists the app's string files, or checks translations (docs/translating.md).

Usage:
  scripts/translations.py            lists every English string file and its size
  scripts/translations.py de         checks the `values-de` folders
  scripts/translations.py all        checks every language in LANGUAGES

It exits 1, and prints the commands that fix it, when a language is missing a string, still
has a string that's gone from English, has placeholders (%1$s, %d, %%) that differ from the
English, lacks a plural quantity the language needs, has broken escaping or isn't valid XML.
Text that looks untranslated (still English, or the wrong script) is only a warning.
CI runs `scripts/translations.py all` through scripts/check.sh. It needs only Python 3.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Every translated language: Android folder qualifier, the desktop app's code for it
# (packages/app/src/runtime/i18n/<code>.ts in github.com/anomalyco/opencode), the language tag dates and
# numbers use (core_language_tag), its English name and the script its text is written in.
LANGUAGES = [
    ("am", "am", "am", "Amharic", "Ethiopic"),
    ("az", "az", "az", "Azerbaijani", "Latin"),
    ("bg", "bg", "bg", "Bulgarian", "Cyrillic"),
    ("bn", "bn", "bn", "Bengali", "Bengali"),
    ("bs", "bs", "bs", "Bosnian", "Latin"),
    ("ca", "ca", "ca", "Catalan", "Latin"),
    ("cs", "cs", "cs", "Czech", "Latin"),
    ("da", "da", "da", "Danish", "Latin"),
    ("de", "de", "de", "German", "Latin"),
    ("dz", "dz", "dz", "Dzongkha", "Tibetan"),
    ("el", "el", "el", "Greek", "Greek"),
    ("es", "es", "es", "Spanish", "Latin"),
    ("et", "et", "et", "Estonian", "Latin"),
    ("fi", "fi", "fi", "Finnish", "Latin"),
    ("fo", "fo", "fo", "Faroese", "Latin"),
    ("fr", "fr", "fr", "French", "Latin"),
    ("hi", "hi", "hi", "Hindi", "Devanagari"),
    ("hr", "hr", "hr", "Croatian", "Latin"),
    ("hu", "hu", "hu", "Hungarian", "Latin"),
    ("hy", "hy", "hy", "Armenian", "Armenian"),
    # Android folders use the old code `in` for Indonesian.
    ("in", "id", "id", "Indonesian", "Latin"),
    ("is", "is", "is", "Icelandic", "Latin"),
    ("it", "it", "it", "Italian", "Latin"),
    ("ja", "ja", "ja", "Japanese", "Japanese"),
    ("ka", "ka", "ka", "Georgian", "Georgian"),
    ("km", "km", "km", "Khmer", "Khmer"),
    ("ko", "ko", "ko", "Korean", "Hangul"),
    ("lo", "lo", "lo", "Lao", "Lao"),
    ("lt", "lt", "lt", "Lithuanian", "Latin"),
    ("lv", "lv", "lv", "Latvian", "Latin"),
    ("mk", "mk", "mk", "Macedonian", "Cyrillic"),
    ("mn", "mn", "mn", "Mongolian", "Cyrillic"),
    ("ms", "ms", "ms", "Malay", "Latin"),
    ("my", "my", "my", "Burmese", "Myanmar"),
    ("nb", "no", "nb", "Norwegian Bokmål", "Latin"),
    ("ne", "ne", "ne", "Nepali", "Devanagari"),
    ("nl", "nl", "nl", "Dutch", "Latin"),
    ("pl", "pl", "pl", "Polish", "Latin"),
    ("pt-rBR", "br", "pt-BR", "Brazilian Portuguese", "Latin"),
    ("ro", "ro", "ro", "Romanian", "Latin"),
    ("ru", "ru", "ru", "Russian", "Cyrillic"),
    ("si", "si", "si", "Sinhala", "Sinhala"),
    ("sk", "sk", "sk", "Slovak", "Latin"),
    ("sl", "sl", "sl", "Slovenian", "Latin"),
    ("sq", "sq", "sq", "Albanian", "Latin"),
    ("sr", "sr", "sr", "Serbian (Cyrillic)", "Cyrillic"),
    ("sv", "sv", "sv", "Swedish", "Latin"),
    ("tg", "tg", "tg", "Tajik", "Cyrillic"),
    ("th", "th", "th", "Thai", "Thai"),
    ("tk", "tk", "tk", "Turkmen", "Latin"),
    ("tr", "tr", "tr", "Turkish", "Latin"),
    ("uk", "uk", "uk", "Ukrainian", "Cyrillic"),
    ("uz", "uz", "uz", "Uzbek (Latin)", "Latin"),
    ("vi", "vi", "vi", "Vietnamese", "Latin"),
    ("zh-rCN", "zh", "zh-Hans", "Simplified Chinese", "Han"),
    ("zh-rTW", "zht", "zh-Hant", "Traditional Chinese", "Han"),
]

# The plural quantities each language needs (CLDR cardinal rules); any other language has
# one and other.
PLURAL_QUANTITIES = {
    "dz": ["other"],
    "in": ["other"],
    "ja": ["other"],
    "km": ["other"],
    "ko": ["other"],
    "lo": ["other"],
    "ms": ["other"],
    "my": ["other"],
    "th": ["other"],
    "vi": ["other"],
    "zh-rCN": ["other"],
    "zh-rTW": ["other"],
    "ca": ["one", "many", "other"],
    "es": ["one", "many", "other"],
    "fr": ["one", "many", "other"],
    "it": ["one", "many", "other"],
    "pt-rBR": ["one", "many", "other"],
    "bs": ["one", "few", "other"],
    "hr": ["one", "few", "other"],
    "ro": ["one", "few", "other"],
    "sr": ["one", "few", "other"],
    "cs": ["one", "few", "many", "other"],
    "lt": ["one", "few", "many", "other"],
    "pl": ["one", "few", "many", "other"],
    "ru": ["one", "few", "many", "other"],
    "sk": ["one", "few", "many", "other"],
    "uk": ["one", "few", "many", "other"],
    "sl": ["one", "two", "few", "other"],
    "lv": ["zero", "one", "other"],
}

# Unicode ranges a language's text must use at least once when the English has words.
SCRIPT_RANGES = {
    "Ethiopic": [(0x1200, 0x139F)],
    "Bengali": [(0x0980, 0x09FF)],
    "Cyrillic": [(0x0400, 0x04FF)],
    "Tibetan": [(0x0F00, 0x0FFF)],
    "Greek": [(0x0370, 0x03FF)],
    "Devanagari": [(0x0900, 0x097F)],
    "Armenian": [(0x0530, 0x058F)],
    "Japanese": [(0x3040, 0x30FF), (0x4E00, 0x9FFF)],
    "Georgian": [(0x10A0, 0x10FF)],
    "Khmer": [(0x1780, 0x17FF)],
    "Hangul": [(0xAC00, 0xD7AF), (0x1100, 0x11FF)],
    "Lao": [(0x0E80, 0x0EFF)],
    "Myanmar": [(0x1000, 0x109F)],
    "Sinhala": [(0x0D80, 0x0DFF)],
    "Thai": [(0x0E00, 0x0E7F)],
    "Han": [(0x4E00, 0x9FFF)],
}

# Names and terms that stay as written in every language, so text made only of them may match
# the English.
KEEP_TERMS = [
    "OpenCode", "opencode pair --remote", "opencode", "Claude", "GPT", "Gemini", "macOS", "Linux",
    "Windows", "Android", "iOS", "Tailscale", "Wi-Fi", "GitHub", "Git", "MCP", "LSP", "SSH",
    "HTTP", "HTTPS", "URL", "QR", "API", "CLI", "TUI", "OAuth", "JSON", "Markdown", "Webview",
    "WebRTC", "KB", "MB", "GB", "TB", "ID", "Pull request", "Shell", "shell",
    # Sound pack names may be translated or kept.
    "Breath Ping", "Crystal Glass", "Warm Analog", "Aurora Pulse", "Velvet Bell",
]


def english_files():
    files = []
    for path in sorted(ROOT.glob("**/src/main/res/values/strings*.xml")):
        if "build" in path.relative_to(ROOT).parts:
            continue
        files.append(path)
    return files


def translated_path(english_path, qualifier):
    return english_path.parent.parent / f"values-{qualifier}" / english_path.name


def plural_quantities(qualifier):
    return PLURAL_QUANTITIES.get(qualifier, ["one", "other"])


def placeholders(text):
    return sorted(re.findall(r"%%|%\d+\$[sdf]|%[sdf]", text))


def has_stray_percent(text):
    without = re.sub(r"%%|%\d+\$[sdf]|%[sdf]", "", text)
    return "%" in without


def read_resources(path):
    """Returns the file's comments, strings and plurals in order.

    Each item is a dict with "kind" ("comment", "string" or "plurals"), "blank_before" (a blank
    line comes before it) and, for strings and plurals, "name", "translatable" and the text as it
    reads in the XML file: "text" for a string, "items" (quantity -> text) for a plural.
    """
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.parse(path, parser).getroot()
    items = []
    previous_tail = root.text or ""
    for element in root:
        item = {"blank_before": previous_tail.count("\n") > 1}
        previous_tail = element.tail or ""
        if element.tag is ET.Comment:
            item["kind"] = "comment"
            item["text"] = element.text
        elif element.tag == "string":
            item["kind"] = "string"
            item["name"] = element.get("name")
            item["translatable"] = element.get("translatable") != "false"
            item["text"] = "".join(element.itertext())
        elif element.tag == "plurals":
            item["kind"] = "plurals"
            item["name"] = element.get("name")
            item["translatable"] = element.get("translatable") != "false"
            item["items"] = {}
            for child in element:
                item["items"][child.get("quantity")] = "".join(child.itertext())
        else:
            continue
        items.append(item)
    return items


def read_strings(path):
    """Returns name -> item for the file's translatable strings and plurals."""
    entries = {}
    for item in read_resources(path):
        if item["kind"] == "comment" or not item["translatable"]:
            continue
        entries[item["name"]] = item
    return entries


def texts(item):
    if item["kind"] == "string":
        return [item["text"]]
    return list(item["items"].values())


def strip_kept(text):
    """The text without placeholders, escapes and KEEP_TERMS."""
    text = re.sub(r"%%|%\d+\$[sdf]|%[sdf]|\\n", " ", text)
    for term in KEEP_TERMS:
        text = text.replace(term, " ")
    return text


def english_words(text):
    """The English words in a text, leaving out placeholders and KEEP_TERMS."""
    return [word for word in re.findall(r"[A-Za-z]{3,}", strip_kept(text)) if not word.isupper()]


def uses_script(text, script):
    for character in text:
        for start, end in SCRIPT_RANGES[script]:
            if start <= ord(character) <= end:
                return True
    return False


def problems(english, translated, qualifier, script):
    """Returns two lists of sentences: what's wrong with one translated string or plural, and
    what only looks wrong (warnings)."""
    errors = []
    warnings = []
    if english["kind"] != translated["kind"]:
        return [f"is a {translated['kind']} but the English is a {english['kind']}"], []
    if english["kind"] == "string":
        wanted = placeholders(english["text"])
    else:
        wanted = placeholders(english["items"]["other"])
        quantities = plural_quantities(qualifier)
        for quantity in quantities:
            if quantity not in translated["items"]:
                errors.append(f"lacks the plural quantity {quantity}")
        for quantity in translated["items"]:
            if quantity not in quantities:
                errors.append(f"has the plural quantity {quantity}, which the language doesn't use")
    english_text = " ".join(texts(english))
    words = english_words(english_text)
    for text in texts(translated):
        if placeholders(text) != wanted:
            errors.append(f"has placeholders {placeholders(text)} instead of {wanted}: {text}")
        if has_stray_percent(text):
            errors.append(f"has a % that isn't a placeholder (write %%): {text}")
        if re.search(r"(?<!\\)['\"]", text):
            errors.append(f"has an unescaped quote (write \\' or \\\"): {text}")
        if text.startswith("@") or text.startswith("?"):
            errors.append(f"starts with an unescaped @ or ? (write \\@ or \\?): {text}")
        if not words:
            continue
        has_letters = any(character.isalpha() for character in strip_kept(text))
        if script != "Latin" and has_letters and not uses_script(text, script):
            warnings.append(f"isn't written in {script} script: {text}")
        same_as_english = text.strip() in [t.strip() for t in texts(english)]
        if same_as_english and (script != "Latin" or len(english_words(text)) > 1):
            warnings.append(f"is still in English: {text}")
    return errors, warnings


def app_languages():
    """The qualifiers listed in appLanguages in app/build.gradle.kts."""
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    match = re.search(r"val appLanguages = listOf\(([^)]*)\)", gradle)
    return re.findall(r'"([^"]+)"', match.group(1))


def list_files():
    total = 0
    for path in english_files():
        count = len(read_strings(path))
        total += count
        print(f"{count:5}  {path.relative_to(ROOT)}")
    print(f"{total:5}  strings to translate")


class Report:
    """What one language's check found."""

    def __init__(self, qualifier):
        self.qualifier = qualifier
        self.missing = 0
        self.removed = 0
        self.broken = []
        self.other = 0
        self.warnings = 0


def check_language(qualifier):
    script = "Latin"
    language_tag = None
    for language in LANGUAGES:
        if language[0] == qualifier:
            language_tag = language[2]
            script = language[4]
    report = Report(qualifier)
    if qualifier not in app_languages():
        print(f"app/build.gradle.kts: {qualifier} isn't in appLanguages")
        report.other += 1
    for english_path in english_files():
        english = read_strings(english_path)
        path = translated_path(english_path, qualifier)
        relative = path.relative_to(ROOT)
        if not path.exists():
            print(f"{relative}: file missing ({len(english)} strings)")
            report.missing += len(english)
            continue
        try:
            translated = read_strings(path)
        except ET.ParseError as error:
            print(f"{relative}: not valid XML: {error}")
            report.other += 1
            continue
        for name in english:
            if name not in translated:
                print(f"{relative}: missing {name}")
                report.missing += 1
                continue
            if name == "core_language_tag":
                if language_tag is not None and translated[name]["text"] != language_tag:
                    print(f"{relative}: core_language_tag should be {language_tag}")
                    report.other += 1
                continue
            errors, warnings = problems(english[name], translated[name], qualifier, script)
            for problem in errors:
                print(f"{relative}: {name} {problem}")
            if errors:
                report.broken.append(name)
            for problem in warnings:
                print(f"{relative}: warning: {name} {problem}")
                report.warnings += 1
        for name in translated:
            if name not in english:
                print(f"{relative}: {name} no longer exists in English")
                report.removed += 1
    return report


def print_fixes(reports):
    print()
    print("Translations need fixing (docs/translating.md):")
    if any(report.missing or report.removed for report in reports):
        print("- Missing strings, or strings gone from English: run")
        print("    python3 scripts/translate.py all")
        print("  It translates the missing strings with a model and drops the removed ones. Without")
        print("  Anomaly Console access, add --model <provider/model> to use your own, or leave the")
        print("  missing strings for a maintainer to translate.")
    broken = [report for report in reports if report.broken]
    if broken:
        print("- Broken placeholders, plurals or escaping: fix them by hand, or translate them again:")
        for report in broken:
            redo = " ".join(f"--redo {name}" for name in report.broken)
            print(f"    python3 scripts/translate.py {report.qualifier} {redo}")
    if any(report.other for report in reports):
        print("- Invalid XML, core_language_tag or appLanguages: fix the file named above by hand.")
    print("Then check again: python3 scripts/translations.py all")


def main():
    if len(sys.argv) == 1:
        list_files()
        return 0
    if sys.argv[1] == "all":
        qualifiers = [language[0] for language in LANGUAGES]
    else:
        qualifiers = sys.argv[1:]
    failed = []
    warnings = 0
    for qualifier in qualifiers:
        report = check_language(qualifier)
        warnings += report.warnings
        problem_count = report.missing + report.removed + len(report.broken) + report.other
        if problem_count:
            failed.append(report)
        if problem_count or len(qualifiers) == 1:
            print(f"{qualifier}: {report.missing} missing, {report.removed} removed from English, "
                  f"{len(report.broken) + report.other} to fix")
    if failed:
        print_fixes(failed)
        return 1
    print(f"Translations OK: {len(qualifiers)} language{"" if len(qualifiers) == 1 else "s"}, {warnings} warnings.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
