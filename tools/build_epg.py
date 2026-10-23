#!/usr/bin/env python3
"""
Programmführer (EPG) für die Live-Sender von Mediathek TV.

Quelle: frei verfügbare XMLTV-Programmlisten von epgshare01.online (Deutschland + Schweiz),
dieselben, die viele IPTV-Apps wie TiviMate nutzen. Behalten werden nur unsere Sender und
die Sendungen von vor 2 Stunden bis 30 Stunden in die Zukunft.

Ausgabe: epg.json.gz  {"generated": …, "ch": {"<Sendername wie im Katalog>": [[start, ende, titel, untertitel, text], …]}}
Aufruf:  python3 tools/build_epg.py --catalog out/index.json.gz --out out/epg.json.gz
"""
import argparse, gzip, json, re, sys, time, urllib.request
from datetime import datetime
from xml.etree import ElementTree as ET

SOURCES = [
    "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_CH1.xml.gz",
]
NOW = int(time.time())
WINDOW = (NOW - 2 * 3600, NOW + 30 * 3600)

# Sendername im Katalog -> mögliche Kennungen in den XMLTV-Listen (erste vorhandene gewinnt).
# Regionalfenster (NDR Hamburg, WDR Lokalzeit …) zeigen das Programm ihres Hauptsenders.
MAPPING = [
    (r"^Das Erste$", ["Das.Erste.de", "Das.Erste.ch"]),
    (r"^ZDF$", ["ZDF.de", "ZDF.ch"]),
    (r"^ZDFneo$", ["ZDFneo.de", "ZDFneo.ch"]),
    (r"^ZDFinfo$", ["ZDFinfo.de", "ZDFinfo.ch"]),
    (r"^3sat$", ["3sat.de", "3sat.ch"]),
    (r"^ARTE$", ["ARTE.de", "Arte.Deutsch.ch"]),
    (r"^ONE$", ["ONE.de", "One.ch"]),
    (r"^tagesschau24$", ["tagesschau24.de", "Tagesschau24.ch"]),
    (r"^phoenix$", ["PHOENIX.de", "Phoenix.TV.ch"]),
    (r"^ARD alpha$", ["ARD.alpha.de", "ARD-alpha.ch"]),
    (r"^BR", ["BR.de", "BR.ch"]),
    (r"^HR", ["HR.de", "HR.ch"]),
    (r"^MDR", ["MDR.de", "MDR.ch"]),
    (r"^NDR", ["NDR.de", "NDR.ch"]),
    (r"^RBB", ["RBB.de", "RBB.ch"]),
    (r"^SWR", ["SWR/SR.de", "SWR.ch"]),
    (r"^SR$", ["SR.Fernsehen.ch", "SWR/SR.de"]),
    (r"^WDR", ["WDR.de", "WDR.ch"]),
    (r"^Radio Bremen", ["Radio.Bremen.TV.de"]),
    (r"^ORF 1$", ["ORF.1.de", "ORF.1.ch"]),
    (r"^ORF 2$", ["ORF.2.de", "ORF.2.ch"]),
    (r"^ORF III$", ["ORF.III.de", "ORF.3.ch"]),
    (r"^ORF Sport", ["ORF.SPORT.+.de", "ORF.Sport.+.ch"]),
    (r"^SRF 1$", ["SRF.1.de", "SRF.1.ch"]),
    (r"^SRF zwei$", ["SRF.zwei.de", "SRF.zwei.ch"]),
    (r"^SRF info$", ["SRF.info.ch"]),
]

def ts(xmltv_time):
    """'20261021030000 +0000' -> Unix-Sekunden"""
    return int(datetime.strptime(xmltv_time.strip(), "%Y%m%d%H%M%S %z").timestamp())

def load_programmes():
    """Alle Sendungen aus den Quellen: XMLTV-Kennung -> Liste."""
    by_id = {}
    for url in SOURCES:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (MediathekTV-EPG)"})
            data = gzip.decompress(urllib.request.urlopen(req, timeout=120).read())
        except Exception as e:
            print(f"EPG-Quelle nicht erreichbar ({url}): {e}", file=sys.stderr)
            continue
        for p in ET.fromstring(data).iter("programme"):
            try:
                start, stop = ts(p.get("start")), ts(p.get("stop"))
            except (TypeError, ValueError):
                continue
            if stop < WINDOW[0] or start > WINDOW[1]:
                continue
            desc = (p.findtext("desc") or "").replace("\\n", " ").strip()
            if len(desc) > 220:
                desc = desc[:220].rsplit(" ", 1)[0] + " …"
            by_id.setdefault(p.get("channel"), []).append(
                [start, stop, (p.findtext("title") or "").strip(), (p.findtext("sub-title") or "").strip(), desc])
    return by_id

def build(live_names, by_id):
    out = {}
    for name in live_names:
        for pattern, ids in MAPPING:
            if re.search(pattern, name):
                found = next((by_id[i] for i in ids if by_id.get(i)), None)
                if found:
                    seen, progs = set(), []
                    for p in sorted(found):
                        if p[0] not in seen:          # Doppelte aus mehreren Quellen entfernen
                            seen.add(p[0]); progs.append(p)
                    out[name] = progs
                break
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", required=True, help="index.json.gz (für die Liste der Live-Sender)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    with gzip.open(a.catalog, "rt", encoding="utf-8") as f:
        live = [c["n"] for c in json.load(f).get("live", [])]
    epg = build(live, load_programmes())
    with gzip.open(a.out, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump({"generated": NOW, "ch": epg}, f, ensure_ascii=False, separators=(",", ":"))
    missing = [n for n in live if n not in epg]
    print(f"EPG: {len(epg)}/{len(live)} Sender, {sum(len(v) for v in epg.values())} Sendungen"
          + (f"; ohne Programm: {', '.join(missing)}" if missing else ""), file=sys.stderr)

if __name__ == "__main__":
    main()
