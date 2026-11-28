#!/usr/bin/env python3
"""Generates external Badr 26.0E + Es'hailSat 25.5E channel logo TSV index."""
import re
import urllib.request
from pathlib import Path

OUT_FILE = Path("zen4-companion/badr-eshail-logos.tsv")
PICONS_SNP_URL = "https://raw.githubusercontent.com/picons/picons/master/build-source/snp.index"
PICONS_LOGO_BASE = "https://raw.githubusercontent.com/picons/picons/master/build-source/logos/"
MINITV_BASE = "https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/"

PRIORITY_MINITV = {
    "2m": "2m.png",
    "2m maroc": "2m.png",
    "al aoula": "al-aoula.png",
    "arryadia": "arryadia.png",
    "mbc 1": "mbc1.png",
    "mbc1": "mbc1.png",
    "mbc 2": "mbc2.png",
    "mbc2": "mbc2.png",
    "mbc 3": "mbc3.png",
    "mbc3": "mbc3.png",
    "mbc 4": "mbc4.png",
    "mbc4": "mbc4.png",
    "mbc 5": "mbc5.png",
    "mbc5": "mbc5.png",
    "medi 1 tv": "medi1.png",
    "medi1": "medi1.png",
}

SUFFIX_RE = re.compile(r"\b(4k|uhd|fhd|hd|sd|hevc|h265)\b", re.I)
NON_ALNUM_RE = re.compile(r"[^a-z0-9\u0600-\u06FF]+")


def normalize(name: str) -> str:
    s = name.lower().replace("\u00a0", " ").replace("_", " ")
    s = SUFFIX_RE.sub(" ", s)
    s = NON_ALNUM_RE.sub(" ", s).strip()
    return re.sub(r"\s+", " ", s)


def main() -> None:
    entries: dict[str, str] = {}
    for k, fname in PRIORITY_MINITV.items():
        entries[normalize(k)] = MINITV_BASE + fname

    with urllib.request.urlopen(PICONS_SNP_URL, timeout=20) as resp:
        text = resp.read().decode("utf-8", errors="replace")
    for line in text.splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        alias, target = line.split("=", 1)
        key = normalize(alias)
        logo = target.strip()
        if not key or not logo:
            continue
        if not logo.endswith(".png"):
            logo = f"{logo}.default.png"
        entries.setdefault(key, PICONS_LOGO_BASE + logo)

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Badr 26.0E + Es'hailSat 25.5E External Channel Logo Index",
        "# Format: normalized_channel_name<TAB>https://raw.githubusercontent.com/.../*.png",
    ] + [f"{k}\t{entries[k]}" for k in sorted(entries)]
    OUT_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {len(entries)} entries to {OUT_FILE}")


if __name__ == "__main__":
    main()
