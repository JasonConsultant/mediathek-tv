#!/usr/bin/env python3
"""
Baut den Katalog für Mediathek TV aus der offiziellen MediathekView-Filmliste.

Ablauf
  1. Filmliste laden (alle öffentlich-rechtlichen Mediatheken, ~700.000 Einträge)
  2. Deutschsprachige Sender behalten, Kinderprogramm/Nachrichten/Sport aussortieren
  3. Fassungen (Originalton, Hörfassung …) bündeln, Wiederholungen zusammenfassen
  4. Einteilen in Filme, Krimis, Dokus, Serien, Film-Reihen, Doku-Reihen
     Serie vs. Doku: kleiner Text-Klassifikator, der bei jedem Lauf aus den
     Einträgen mit bekannter Kategorie (ZDF, ARTE, 3sat) neu lernt.
  5. catalog.json.gz schreiben

Ausgabe (Ordner):
  index.json.gz      Filme, Krimis, Dokus + Kopfdaten aller Serien/Reihen
  s/<id>.json.gz     Folgen einer Serie (lädt die App erst beim Öffnen)

"Neu": Jede Sendung trägt "fs" = Zeitpunkt, an dem sie erstmals im Katalog auftauchte.
Dafür wird der vorherige Katalog (--previous) eingelesen und fortgeschrieben.

Aufruf: python3 tools/build_catalog.py [--input Filmliste-akt.xz] [--previous alter_katalog] [--out catalog]
"""
import argparse, glob, gzip, hashlib, html, json, lzma, os, re, shutil, sys, time, urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from collections import Counter, defaultdict

FILMLISTE_URL = "https://liste.mediathekview.de/Filmliste-akt.xz"
NOW = int(time.time())
YEAR_NOW = time.gmtime().tm_year

# ---------------------------------------------------------------- Einlesen

def load(path):
    if path is None:
        print("Lade Filmliste …", file=sys.stderr)
        data = urllib.request.urlopen(FILMLISTE_URL, timeout=300).read()
        text = lzma.decompress(data).decode("utf-8")
    else:
        text = lzma.open(path, "rt", encoding="utf-8").read()
    dec, pos, ch, th, out = json.JSONDecoder(), 0, "", "", []
    key = '"X":'
    while True:
        i = text.find(key, pos)
        if i < 0:
            break
        a, pos = dec.raw_decode(text, i + len(key))
        ch = a[0] or ch
        th = a[1] or th
        url = a[8]
        try:
            h, m, s = map(int, a[5].split(":")); secs = h * 3600 + m * 60 + s
        except ValueError:
            secs = 0
        hd = expand(a[14], url)
        # Beste Qualität zuerst; die Normalfassung als Ersatz, falls der Sender die HD-Datei nicht mehr anbietet
        # (beim ZDF häufig: die Folge gibt es noch, nur die 1080p-Datei aus der Filmliste nicht mehr).
        out.append(dict(channel=ch, topic=th.strip(), raw=a[2].strip(), secs=secs, desc=a[7].strip(),
                        url=hd or url, url2=url if hd and hd != url else "",
                        web=a[9], sub=a[10], ts=int(a[16] or 0), geo=a[18]))
    return out

def expand(rel, base):
    """'Url HD' ist oft relativ zu 'Url' kodiert: '<präfixlänge>|<rest>'."""
    if not rel:
        return ""
    if "|" in rel:
        n, rest = rel.split("|", 1)
        return base[:int(n)] + rest if n.isdigit() else ""
    return rel

# ---------------------------------------------------------------- Textauswertung

VERSION = re.compile(r"\s*\((?:Originalversion|Original|OmU|Englisch|Französisch|Audiodeskription|"
                     r"mit Gebärdensprache|Gebärdensprache|mit Untertitel|hörgeschädigt)[^)]*\)"
                     r"|\s*\((?:nld|eng|fra|ita|spa|pol|dan|swe|nor|fin|tur|ara|rus|ukr|OV|OmdU|ÖGS|Hörfassung)\)"
                     r"|^\s*(?:Hörfassung|ÖGS)\s*[:|-]\s*", re.I)
SE = re.compile(r"\s*\(S(\d+)/E(\d+)\)")
PART = re.compile(r"\s*\((\d+)/(\d+)\)")
FOLGE = re.compile(r"\bFolge (\d+)\b")
STAFFEL = re.compile(r"\bStaffel\s+(\d+)", re.I)
STAFFEL_SUFFIX = re.compile(r"\s*[-–:,]?\s*Staffel\s+\d+\s*$", re.I)
FILM_SUFFIX = re.compile(r"\s+-\s+(?:Spielfilm|Fernsehfilm|Kinofilm|Dokumentarfilm),.*$")

def language(raw):
    l = raw.lower()
    if "audiodeskription" in l or "hörfassung" in l: return "Deutsch (Hörfassung)"
    if "gebärden" in l or "ögs" in l: return "Gebärdensprache"
    if "(original" in l or "(omu" in l: return "Originalton"
    if "(englisch" in l or "(eng)" in l: return "Englisch"
    if "(nld)" in l: return "Niederländisch"
    if "(französisch" in l: return "Französisch"
    return "Deutsch"

def base_title(raw): return VERSION.sub("", raw).strip()

def display_title(raw, topic):
    t = FILM_SUFFIX.sub("", PART.sub("", SE.sub("", base_title(raw)))).strip()
    if topic:
        q = re.escape(topic)
        t2 = re.sub(rf"^{q}\s*[-–:|]\s*", "", t, flags=re.I)
        t2 = re.sub(rf"\s*[-–|]\s*{q}$", "", t2, flags=re.I).strip()
        t = t2 or t
    return t

def episode(raw, topic):
    m = SE.search(raw)
    if m: return int(m[1]), int(m[2])
    m = PART.search(raw) or FOLGE.search(raw)
    if not m: return None, None
    s = STAFFEL.search(raw) or STAFFEL.search(topic)
    return (int(s[1]) if s else None), int(m[1])

YEAR_PATTERNS = [
    re.compile(r"(?:aus dem Jahr|im Jahr|Produktionsjahr|Jahrgang)\s+((?:19|20)\d{2})\b"),
    re.compile(r"\b(?:Drama|Komödie|Thriller|Film|Spielfilm|Krimi|Western|Dokumentarfilm|Comedy-Drama|Tragikomödie|Liebesfilm)\b[^.]{0,60}?\bvon\s+((?:19|20)\d{2})\b"),
    re.compile(r"\b(?:Deutschland|Österreich|Schweiz|Frankreich|Italien|Spanien|USA|Großbritannien|Kanada|Dänemark|Schweden|Norwegen|Finnland|Belgien|Niederlande|Polen|Tschechien|Irland|Australien|Japan|Südkorea|Island|Ungarn|Israel|Luxemburg|Argentinien|Mexiko)(?:\s*/\s*[A-ZÄÖÜ][a-zäöü]+)*\s*,?\s+((?:19|20)\d{2})\b"),
]
def year(raw, desc):
    text = f"{raw}. {desc}"
    for p in YEAR_PATTERNS:
        m = p.search(text)
        if m and 1900 <= int(m[1]) <= YEAR_NOW: return int(m[1])
    return None

GENRES = [(re.compile(rf"\b(?:{p})", re.I), g) for p, g in [
    ("Tragikomödie", "Tragikomödie"), ("Komödie|Comedy|Komödiantisch", "Komödie"),
    ("Psychothriller|Thriller", "Thriller"), ("Kriminalfilm|Krimi|Tatort|Polizeiruf", "Krimi"),
    ("Science-Fiction|Science Fiction|Sci-Fi", "Science-Fiction"), ("Horrorfilm|Horror", "Horror"),
    ("Western", "Western"), ("Abenteuerfilm|Abenteuer", "Abenteuer"), ("Actionfilm|Action-Thriller", "Action"),
    ("Animationsfilm|Zeichentrick", "Animation"), ("Liebesfilm|Romanze|Liebeskomödie", "Liebesfilm"),
    ("Familienfilm", "Familie"), ("Musikfilm|Musical", "Musik"), ("Historienfilm|Biopic|Filmbiografie", "Historie"),
    ("Dokumentarfilm", "Dokumentarfilm"), ("Drama", "Drama")]]
def genre(raw, desc):
    for r, g in GENRES:
        if r.search(desc) or r.search(raw): return g
    return None

def series_name_from_title(raw):
    """'Haus aus Glas (2/6) - Die Nachfolge' -> 'Haus aus Glas'"""
    t = base_title(raw)
    m = PART.search(t) or SE.search(t)
    return STAFFEL_SUFFIX.sub("", t[:m.start()]).strip() if m else None

def norm(s): return re.sub(r"[^a-z0-9äöüß]", "", s.lower())

# ---------------------------------------------------------------- Regeln

EXCLUDED_CHANNELS = {"ARTE.FR", "ARTE.EN", "ARTE.ES", "ARTE.IT", "ARTE.PL", "KiKA", "ZDF-tivi", "DW", "rbtv", "RBTV"}
CHANNEL_ORDER = ["ARD", "ZDF", "ARTE.DE", "3Sat", "ZDFneo", "ONE", "SRF", "ORF", "ZDFinfo"]
def channel_rank(c): return CHANNEL_ORDER.index(c) if c in CHANNEL_ORDER else len(CHANNEL_ORDER)

FILM_TOPICS = {
    "Filme in der ARD", "Filme im Ersten", "FilmMittwoch im Ersten", "Film im NDR", "Debüt im Dritten", "ARD Debüt",
    "Film & Fiktion", "Filme", "Spielfilm-Highlights", "Herzkino", "Das kleine Fernsehspiel", "Kino - Filme",
    "Fernsehfilme und Serien - Fernsehfilme", "Spielfilm", "Fernsehfilm", "Spielfilm in 3sat", "Fernsehfilm in 3sat",
    "Der Fernsehfilm der Woche", "3satPublikumspreis", "Film", "Schweizer Film", "Cinema Strikes Back",
}
CRIME_FILM_TOPICS = {"Krimi und Thriller"}
CRIME_TOPIC = re.compile(r"^(Tatort|Tatort aus Österreich|Polizeiruf 110|LandKrimi|Wilsberg|Ein starkes Team|"
                         r"Der Kommissar und .*|.*-Krimi|.*krimi|Ein Krimi aus .*|Der Krimi .*)$", re.I)
CRIME_WORDS = re.compile(r"\b(ermittel\w*|Kommissar\w*|Mord\w*|Leiche|Tatort|Täter\w*|Kripo|Mordkommission|Verbrechen)\b", re.I)
DOC_TOPIC = re.compile(r"^(Dokumentationen|Dokumentarfilm|Dokumentarfilmzeit|DOK|Kulturdoku|Universum|Universum History|"
                       r"Terra X|Terra X History|Expeditionen|Expeditionen ins Tierreich|Expedition in die Heimat|"
                       r"Dokus & Reportagen im BR|Doku & Reportage|ZDFinfo Doku|ZDFinfo - die Einzeldokus|Dokus|"
                       r"ZDFzeit|37 Grad|.* - die Einzeldokus|Entdeckung der Welt - .*|Wissenschaft - .*|Geschichte - .*)$")
# Alles, was weder Serie noch Doku-Reihe ist: Nachrichten, Talk, Magazine, Sport, Shows, Service
NOT_SERIES = re.compile(
    r"journal|magazin|kulturzeit|\bnano\b|hallo deutschland|bares für rares|küchenschlacht|illner|lanz|maischberger|"
    r"tagesschau|tagesthemen|\bheute\b|nachrichten|\bnews\b|aktuell|\bsport|quiz|talk|\bshow\b|yoga|ernährungs|"
    r"werwölfe|let's talk|wetter|börse|gottesdienst|landesschau|hier und heute|morgenmagazin|mittagsmagazin|"
    r"\blive\b|parlament|phoenix|kaffee oder tee|volle kanne|fernsehgarten|nachtcaf|\bwahl\b|corona|forum|"
    r"concert|konzert|olympia|paralympics|\bwm\b|\bem\b|bundesliga|spezial|presseclub|arena|club|jass|gala|"
    r"comedy|kabarett|satire|1, 2 oder 3|kika|rezept|küche|kochen|lecker|\bcheck\b|garten|traumhäuser|"
    r"\bzoo|& co\.|podcast|kurzfilm|lebenslinien|kurzstrecke|sendung mit|hautnah|sofa|chuchi|\bleute\b|"
    r"late night|ding dong|köstlich|deutschrap|1 gegen 100|^die frage$|zeuge der zeit|hofgeschichten|"
    r"schlagzeilen|gebärdensprache|reportage|\bdoku|unterwegs|auf der suche|bounce|echtes leben|kochs |^dok\b|bi de lüt|trailer|bühnenprogramm|hobbymania|bürgerbüro", re.I)
# Sammel-Rubriken: kein eigener Serienname
GENERIC_TOPICS = re.compile(r"^(Serien|Spielfilme|Filme & Serien.*|Familienfilme.*|Fernsehfilme und Serien - .*|Kino - .*|Filme, Dokus, Kabarett)$")
ARTE_SERIES = "Fernsehfilme und Serien - Serien"
# Wahrscheinlichkeit "Serie": darüber Serie, darunter (DOC_THRESHOLD) Doku-Reihe, dazwischen unsicher -> weglassen
FICTION_THRESHOLD = 0.55
DOC_THRESHOLD = 0.35
WEB_EXCLUDE = re.compile(r"kika\.de|zdf\.de/(?:kinder|nachrichten|sport|politik|video/magazine|video/shows|video/kurzfassungen)|"
                         r"3sat\.de/(?:kabarett)|zdf\.de/video/game-two")

EU_COUNTRIES = {"DE", "AT", "FR", "IT", "NL", "BE", "LU", "ES", "PT", "DK", "SE", "FI", "PL", "CZ", "IE"}
def playable(geo, country):
    """Leeres Geo-Feld = weltweit. Sonst z.B. 'DE-AT-CH' oder 'DE-AT-CH-EU'."""
    if not geo: return True
    parts = geo.split("-")
    return country in parts or ("EU" in parts and country in EU_COUNTRIES)

def available(o, country):
    return playable(o.get("g", ""), country) or any(playable(a.get("g", ""), country) for a in o.get("a", []))

# ---------------------------------------------------------------- Aufbereitung

def prepare(rows):
    out = []
    for r in rows:
        if r["channel"] in EXCLUDED_CHANNELS or not r["url"] or r["secs"] < 600 or r["ts"] > NOW + 3600:
            continue
        if WEB_EXCLUDE.search(r["web"] or ""):
            continue
        r["lang"] = language(r["raw"])
        r["base"] = base_title(r["raw"])
        r["title"] = display_title(r["raw"], r["topic"])
        r["season"], r["ep"] = episode(r["raw"], r["topic"])
        r["id"] = hashlib.sha1(r["url"].encode()).hexdigest()[:12]
        out.append(r)
    return out

def bundle(items):
    """Fassungen bündeln (Hauptfassung Deutsch) und Wiederholungen/Mehrfach-Sender zusammenfassen.
    Gleiche Sendung mit anderer Geo-Freigabe bleibt als Alternative erhalten (für die Schweiz wichtig)."""
    # 1) Sprachfassungen pro Ausstrahlung
    by_version = defaultdict(list)
    for r in items:
        by_version[(r["channel"], r["topic"], r["base"].lower(), r["ts"] // 86400)].append(r)
    shows = []
    for grp in by_version.values():
        main = next((x for x in grp if x["lang"] == "Deutsch"), grp[0])
        main = dict(main)
        seen = {main["lang"]}
        main["variants"] = []
        for x in grp:
            if x is not main and x["lang"] not in seen and x["url"] != main["url"]:
                seen.add(x["lang"]); main["variants"].append(x)
        shows.append(main)
    # 2) Gleiche Sendung (Wiederholung, anderer Sender)
    same = defaultdict(list)
    for s in shows:
        same[(norm(s["topic"]), norm(s["title"]), s["season"], s["ep"])].append(s)
    result = []
    for grp in same.values():
        grp.sort(key=lambda x: (channel_rank(x["channel"]), -len(x["sub"] or ""), -x["ts"]))
        main = dict(grp[0])
        geos = {main["geo"]}
        main["alts"] = []
        for x in grp[1:]:
            if x["geo"] not in geos and len(main["alts"]) < 3:
                geos.add(x["geo"]); main["alts"].append(x)
        main["latest"] = max(x["ts"] for x in grp)
        result.append(main)
    return result

def compact(r, desc_len=420):
    """Kurzform für die JSON-Ausgabe."""
    o = {"i": r["id"], "c": r["channel"], "t": r["topic"], "n": r["title"], "s": r["secs"], "ts": r["ts"],
         "u": r["url"], "_w": r.get("web") or ""}
    d = r["desc"]
    if d: o["d"] = d if len(d) <= desc_len else d[:desc_len].rsplit(" ", 1)[0] + " …"
    if r["sub"]: o["ut"] = r["sub"]
    if r.get("url2"): o["u2"] = r["url2"]
    if r["geo"]: o["g"] = r["geo"]
    y = year(r["raw"], r["desc"]); g = genre(r["raw"], r["desc"])
    if y: o["y"] = y
    if g: o["ge"] = g
    if r["season"] is not None: o["se"] = r["season"]
    if r["ep"] is not None: o["ep"] = r["ep"]
    if r.get("variants"):
        o["v"] = [{"l": v["lang"], "u": v["url"], **({"ut": v["sub"]} if v["sub"] else {}),
                   **({"u2": v["url2"]} if v.get("url2") else {}),
                   **({"g": v["geo"]} if v["geo"] else {})} for v in r["variants"]]
    if r.get("alts"):
        o["a"] = [{"u": a["url"], **({"ut": a["sub"]} if a["sub"] else {}),
                   **({"g": a["geo"]} if a["geo"] else {})} for a in r["alts"]]
    return o

# ---------------------------------------------------------------- Serie oder Doku? (lernt aus gekennzeichneten Reihen)

def known_kind(r):
    w, ch, t = r["web"] or "", r["channel"], r["topic"]
    if ch in ("ZDF", "ZDFneo", "ZDFinfo"):
        if "/serien/" in w: return "fic"
        if "/video/dokus/" in w or "/dokumentation/" in w or "/video/reportagen/" in w: return "doc"
    if ch == "ARTE.DE":
        if t.startswith("Fernsehfilme und Serien"): return "fic"
        if re.match(r"(Geschichte|Wissenschaft|Entdeckung der Welt|Aktuelles und Gesellschaft) - ", t): return "doc"
    if ch == "3Sat":
        if "3sat.de/film/" in w: return "fic"
        if "3sat.de/dokumentation/" in w or "3sat.de/wissen/" in w: return "doc"
    return None

def train_classifier(rows):
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression
    docs, labels = defaultdict(list), {}
    for r in rows:
        k = known_kind(r)
        if k and r["desc"]:
            key = (r["channel"], r["topic"])
            labels[key] = k
            if len(docs[key]) < 40: docs[key].append(r["raw"] + ". " + r["desc"])
    keys = [k for k in docs if len(docs[k]) >= 2]
    vec = TfidfVectorizer(lowercase=True, min_df=2, ngram_range=(1, 2), max_features=60000, sublinear_tf=True)
    X = vec.fit_transform([" ".join(docs[k]) for k in keys])
    model = LogisticRegression(max_iter=2000, class_weight="balanced", C=4).fit(X, [labels[k] == "fic" for k in keys])
    print(f"Klassifikator: {len(keys)} Reihen mit bekannter Kategorie", file=sys.stderr)
    def fiction_probability(group_rows):
        known = Counter(known_kind(r) for r in group_rows if known_kind(r))
        if known: return 1.0 if known.most_common(1)[0][0] == "fic" else 0.0
        text = " ".join(r["raw"] + ". " + r["desc"] for r in group_rows[:40])
        return float(model.predict_proba(vec.transform([text]))[0, 1])
    return fiction_probability

# ---------------------------------------------------------------- Korrekturliste

CORRECTION_KINDS = {"serie", "filmreihe", "dokureihe", "ausblenden"}

def load_corrections(path):
    """tools/corrections.json: {"serie": [...], "filmreihe": [...], "dokureihe": [...], "ausblenden": [...]}
    Namen wie in der App angezeigt (ohne "Staffel n"). Groß-/Kleinschreibung egal."""
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    out = {}
    for kind, names in data.items():
        if kind in CORRECTION_KINDS:
            for n in names:
                out[STAFFEL_SUFFIX.sub("", n).strip().lower()] = kind
    print(f"Korrekturliste: {len(out)} Einträge", file=sys.stderr)
    return out

# ---------------------------------------------------------------- Katalog

def build(rows, corrections=None):
    corrections = corrections or {}
    rows = prepare(rows)
    print(f"Nach Vorfilter: {len(rows)} Einträge", file=sys.stderr)
    fiction_probability = train_classifier(rows)

    by_topic = defaultdict(list)
    for r in rows:
        if r["topic"] == ARTE_SERIES:
            name = series_name_from_title(r["raw"])
            if name:
                r = dict(r, topic=name, arte_series=True)
                r["title"] = display_title(r["raw"], name)
        by_topic[r["topic"]].append(r)

    films, crime, docs = [], [], []
    series, film_series, doc_series = [], [], []

    for topic, items in by_topic.items():
        if not topic:
            continue
        long_items = [r for r in items if r["secs"] >= 60 * 60]

        # Korrekturliste geht vor allen automatischen Regeln
        forced = corrections.get(STAFFEL_SUFFIX.sub("", topic).strip().lower())
        if forced == "ausblenden":
            continue
        if forced:
            crime_like = bool(CRIME_TOPIC.match(topic)) or \
                sum(bool(CRIME_WORDS.search(r["desc"])) for r in items) / len(items) >= 0.5
            {"serie": series, "filmreihe": film_series, "dokureihe": doc_series}[forced].append(
                (topic, items, crime_like and forced != "dokureihe"))
            if forced == "filmreihe" and crime_like:
                crime += long_items
            continue

        if topic in FILM_TOPICS:
            for r in long_items:
                (crime if CRIME_WORDS.search(r["desc"]) and genre(r["raw"], r["desc"]) in ("Krimi", "Thriller") else films).append(r)
            continue
        if topic in CRIME_FILM_TOPICS:
            crime += long_items
            continue
        if DOC_TOPIC.match(topic):
            docs += [r for r in items if r["secs"] >= 40 * 60]
            continue
        if NOT_SERIES.search(topic):
            continue
        if GENERIC_TOPICS.match(topic):
            films += [r for r in long_items if not CRIME_WORDS.search(r["desc"])]
            continue

        # Reihen: eigener Name, mehrere unterschiedliche Titel
        if len({r["base"] for r in items}) < 3:
            continue
        film_len = sum(80 * 60 <= r["secs"] <= 115 * 60 for r in items) / len(items)
        episodic = sum(r["ep"] is not None for r in items) / len(items)
        mid_len = sum(15 * 60 <= r["secs"] <= 75 * 60 for r in items) / len(items)
        p = 1.0 if items[0].get("arte_series") else fiction_probability(items)
        fiction, documentary = p >= FICTION_THRESHOLD, p < DOC_THRESHOLD
        crime_like = bool(CRIME_TOPIC.match(topic)) or \
            sum(bool(CRIME_WORDS.search(r["desc"])) for r in items) / len(items) >= 0.5

        if fiction and film_len >= 0.6:
            film_series.append((topic, items, crime_like))     # z.B. Tatort, Traumschiff
            if crime_like: crime += [r for r in items if r["secs"] >= 60 * 60]
        elif fiction and mid_len >= 0.6 and (episodic >= 0.3 or len(items) >= 6):
            series.append((topic, items, crime_like))          # z.B. Rosenheim-Cops
        elif documentary and mid_len >= 0.5 and episodic >= 0.3:
            doc_series.append((topic, items, False))           # z.B. Terra X

    def make_series(entries, kind, max_eps=400):
        out = []
        for topic, items, crime_like in entries:
            eps = bundle(items)
            if len(eps) < 2:
                continue
            eps = sorted(eps, key=lambda e: -e["ts"])[:max_eps]
            if any(e["ep"] is not None for e in eps) and kind != "filmreihe":
                eps.sort(key=lambda e: ((e["season"] or 0), (e["ep"] if e["ep"] is not None else 10**6), e["ts"]))
            else:
                eps.sort(key=lambda e: -e["ts"])
            ch = Counter(e["channel"] for e in eps).most_common(1)[0][0]
            name = STAFFEL_SUFFIX.sub("", topic).strip()
            out.append({"n": name, "c": ch, "k": kind, **({"cr": 1} if crime_like else {}),
                        "latest": max(e["latest"] for e in eps), "e": [compact(e, 300) for e in eps]})
        # gleiche Serie in mehreren Staffel-Rubriken ("Soko Donau Staffel 9") zusammenführen
        merged = {}
        for s in out:
            m = merged.get(s["n"])
            if m is None:
                merged[s["n"]] = s
            else:
                m["e"] += s["e"]; m["latest"] = max(m["latest"], s["latest"])
                m["e"].sort(key=lambda e: (e.get("se", 0), e.get("ep", 10**6), e["ts"]))
        res = sorted(merged.values(), key=lambda s: -s["latest"])
        for s in res:
            s["id"] = hashlib.sha1(f'{kind}|{s["n"]}'.encode()).hexdigest()[:10]
            s["new"] = s.pop("latest")
        return res

    def make_list(items, limit):
        b = bundle(items)
        b.sort(key=lambda r: -r["latest"])
        return [compact(r) for r in b[:limit]]

    catalog = {
        "version": 2,
        "generated": NOW,
        "films": make_list(films, 4000),
        "crime": make_list(crime, 3000),
        "docs": make_list(docs, 2500),
        "series": make_series(series, "serie"),
        "filmSeries": make_series(film_series, "filmreihe"),
        "docSeries": make_series(doc_series, "doku")[:400],
    }
    return catalog

def write_gz(path, obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()
    with gzip.open(path, "wb", compresslevel=9) as f:
        f.write(raw)
    return len(raw)

# ---------------------------------------------------------------- Vorschaubilder

IMAGE_WIDTH = 480
MAX_NEW_IMAGES = 12000         # pro Lauf (~10 Min.); der Rest folgt in den nächsten Läufen
UA = {"User-Agent": "Mozilla/5.0 (MediathekTV-Katalog)"}
OG_IMAGE = re.compile(rb'<meta[^>]+property="og:image"[^>]+content="([^"]+)"|<meta[^>]+content="([^"]+)"[^>]+property="og:image"')
# Platzhalter der Sender aussortieren ("teaser-mit-logo" beim ZDF ist dagegen ein echtes Bild)
BAD_IMAGE = re.compile(r"fallback|placeholder|/default[-_.]|logo\.(?:png|svg|jpe?g)", re.I)

def _get(url, limit=None, timeout=10):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read(limit) if limit else resp.read()

def _og_image(page_url):
    """Bildadresse aus dem Seitenkopf – es werden nur die ersten 32 KB gelesen."""
    m = OG_IMAGE.search(_get(page_url, 32768))
    if not m:
        return None
    url = html.unescape((m.group(1) or m.group(2)).decode("utf-8", "replace"))
    return url if url.startswith("http") and not BAD_IMAGE.search(url) else None

ARD_FAMILY = {"ARD", "ONE", "ARD-alpha", "BR", "HR", "MDR", "NDR", "RBB", "SR", "SWR", "WDR", "tagesschau24", "PHOENIX", "Radio Bremen TV"}

def ard_search(title):
    """Ausweg, wenn die Sendungsseite nicht mehr existiert: Suche der ARD-Mediathek.
    Nur eindeutige Titel-Treffer werden übernommen (lieber kein Bild als ein falsches)."""
    want = norm(title)
    if len(want) < 6:
        return None
    url = "https://api.ardmediathek.de/search-system/search/vods/ard?pageSize=5&platform=MEDIA_THEK&query=" + urllib.parse.quote(title)
    for t in json.loads(_get(url)).get("teasers", []):
        names = [norm(t.get(k) or "") for k in ("mediumTitle", "shortTitle", "longTitle")]
        if any(n == want or (len(want) >= 10 and n.endswith(want)) for n in names):
            src = ((t.get("images") or {}).get("aspect16x9") or {}).get("src")
            if src:
                return src.replace("{width}", str(IMAGE_WIDTH))
    return None

def find_image(web, title="", channel=""):
    """Bild zur Sendungsseite; bei ARD-Sendern mit verschwundener Seite über die ARD-Suche."""
    error = None
    try:
        img = find_image_on_page(web)
    except Exception as e:
        img, error = None, e
    if not img and channel in ARD_FAMILY and title:
        img = ard_search(title)
    if not img and error:
        raise error
    return img

def find_image_on_page(web):
    """Kleines Vorschaubild (~480 px) zur Sendungsseite, je nach Plattform."""
    host = urllib.parse.urlparse(web).netloc
    if "srf.ch" in host:
        q = urllib.parse.parse_qs(urllib.parse.urlparse(web).query)
        urn = q.get("urn", [None])[0] or (q.get("id", [None])[0] and f"urn:srf:video:{q['id'][0]}")
        if not urn:
            return None
        data = json.loads(_get(f"https://il.srgssr.ch/integrationlayer/2.0/mediaComposition/byUrn/{urn}.json"))
        img = data["chapterList"][0].get("imageUrl")
        return img and f"https://il.srgssr.ch/images/?imageUrl={urllib.parse.quote(img, safe='')}&format=jpg&width={IMAGE_WIDTH}"
    img = _og_image(web)
    if not img:
        return None
    if "ardmediathek.de" in img and "url=" in img:            # ARD verpackt das Bild in einen Proxy-Link
        img = urllib.parse.parse_qs(urllib.parse.urlparse(img).query)["url"][0]
    if "image-service" in img:                                 # ARD-Bilddienst: Breite wählbar
        img = img.replace("{width}", str(IMAGE_WIDTH))
    elif "epg-image.zdf.de" in img:                            # ZDF / 3sat (Bilddatenbank)
        img = re.sub(r"layout=\d+x\d+", "layout=384x216", img)
    elif "zdf.de/assets/" in img:                              # ZDF (Redaktionsbilder)
        img = re.sub(r"~\d+x\d+", "~384x216", img)
    elif "api-cdn.arte.tv/img" in img:                         # ARTE
        img = re.sub(r"/\d+x\d+(\?.*)?$", "/480x270", img)
    return img

IMAGE_CACHE_VERSION = 4   # erhöhen, wenn sich die Bildsuche verbessert -> alte Fehlschläge neu versuchen

def load_image_cache(prev_dir):
    path = prev_dir and os.path.join(prev_dir, "images.json.gz")
    if not path or not os.path.exists(path):
        return {}
    cache = read_gz(path)
    if cache.pop("_v", None) != IMAGE_CACHE_VERSION:
        cache = {k: v for k, v in cache.items() if v and v[0]}      # nur Gefundenes behalten
        print("Bild-Cache: alte Fehlschläge verworfen", file=sys.stderr)
    return cache

def assign_images(cat, cache):
    """Bilder für Filme/Krimis/Dokus und je ein Titelbild pro Serie. Bekanntes aus dem Cache,
    Neues wird parallel gesucht (begrenzt pro Lauf). Cache-Eintrag: [bild_oder_leer, geprüft_am]."""
    # Reihenfolge = Priorität: erst ein Titelbild je Serie, dann Filme/Krimis/Dokus, dann Ersatzkandidaten
    targets = {}                                   # Schlüssel -> Website
    groups = ("series", "filmSeries", "docSeries")
    def target(o): return (o["_w"], o.get("n", ""), o.get("c", ""))
    for group in groups:
        for s in cat[group]:
            e = max(s["e"], key=lambda e: e["ts"])
            if e.get("_w"): targets.setdefault(fs_key(e), target(e))
    for key in ("films", "crime", "docs"):
        for o in cat[key]:
            if o.get("_w"): targets.setdefault(fs_key(o), target(o))
    for group in groups:
        for s in cat[group]:
            for e in sorted(s["e"], key=lambda e: -e["ts"])[1:3]:   # weitere neueste Folgen als Ersatz
                if e.get("_w"): targets.setdefault(fs_key(e), target(e))

    todo = [k for k in targets if k not in cache or (not cache[k][0] and NOW - cache[k][1] > 7 * 86400)]
    todo = todo[:MAX_NEW_IMAGES]
    problems = Counter()                           # Auswertung: warum keine Bilder?
    def work(k):
        web, title, channel = targets[k]
        host = urllib.parse.urlparse(web).netloc
        try:
            img = find_image(web, title, channel) or ""
            if not img: problems[f"{host}: kein Bild auf der Seite"] += 1
            return k, img
        except Exception as e:
            problems[f"{host}: {type(e).__name__} {getattr(e, 'code', '')}".strip()] += 1
            return k, ""
    t = time.time()
    found = 0
    with ThreadPoolExecutor(16) as ex:
        for k, img in ex.map(work, todo):
            cache[k] = [img, NOW]
            found += bool(img)
    print(f"Bilder: {len(targets)} benötigt, {len(todo)} neu gesucht ({found} gefunden) in {time.time()-t:.0f}s", file=sys.stderr)
    for what, n in problems.most_common(12):
        print(f"  ohne Bild: {n:5}  {what}", file=sys.stderr)

    def img_of(o):
        c = cache.get(fs_key(o))
        return c[0] if c and c[0] else None
    covers = {}
    for group in ("series", "filmSeries", "docSeries"):
        for s in cat[group]:
            for e in s["e"]:
                if (im := img_of(e)): e["im"] = im
            cover = next((e["im"] for e in sorted(s["e"], key=lambda e: -e["ts"]) if e.get("im")), None)
            if cover:
                s["im"] = cover
                covers.setdefault(s["n"].lower(), cover)
    fallback = 0
    for key in ("films", "crime", "docs"):
        for o in cat[key]:
            im = img_of(o) or covers.get(STAFFEL_SUFFIX.sub("", o.get("t", "")).strip().lower())   # z.B. Tatort-Reihenbild
            if im:
                o["im"] = im
                fallback += not img_of(o)
    print(f"Bilder: {fallback} Sendungen zeigen das Bild ihrer Reihe", file=sys.stderr)
    # Cache klein halten: nur, was noch im Katalog ist
    return {k: v for k, v in cache.items() if k in targets}

def strip_internal(cat):
    for key in ("films", "crime", "docs"):
        for o in cat[key]: o.pop("_w", None)
    for group in ("series", "filmSeries", "docSeries"):
        for s in cat[group]:
            for e in s["e"]: e.pop("_w", None)

# ---------------------------------------------------------------- Noch abrufbar? (veraltete Einträge der Filmliste aussortieren)

VALID_TTL = 3 * 24 * 3600      # Prüfergebnis gilt 3 Tage
MAX_CHECKS = 4000              # pro Lauf (16 Threads, ~2 Min.); der Rest folgt in den nächsten Läufen
SRF_URN = re.compile(r"urn:srf:video:[0-9a-f-]{36}")

def probe_url(o):
    """Was wir anfragen, um zu wissen, ob die Sendung noch existiert.
    SRF: Datenbank-Eintrag (per URN) oder die Untertitel-Datei – die Videodatei bleibt dort auch nach dem Löschen
    liegen. Alle anderen: die Videodatei selbst. (ARD-Datenbank und ZDF-Sendungsseite wurden probiert und wieder
    verworfen: die Web-Adressen der Filmliste sind oft veraltet und melden 404, obwohl die Sendung noch läuft.)"""
    if o["c"] == "SRF":
        m = SRF_URN.search(o.get("_w") or "")
        if m: return f"https://il.srgssr.ch/integrationlayer/2.0/mediaComposition/byUrn/{m.group(0)}.json?onlyChapters=true"
        return o.get("ut") or o["u"]
    return o["u"]

def http_status(url):
    req = urllib.request.Request(url, headers=UA, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0                                           # Netzfehler: unbekannt

def gone(o, status):
    """Nur 404/410 gelten als weg. 403 ist mehrdeutig (Ländersperre vom Build-Server aus, oder nur die HD-Datei
    fehlt) – das klärt die App vom Gerät aus, notfalls mit der Ersatzadresse."""
    return status in (404, 410)

def load_valid_cache(prev_dir):
    path = prev_dir and os.path.join(prev_dir, "valid.json.gz")
    return read_gz(path) if path and os.path.exists(path) else {}

def validate(cat, cache):
    """Entfernt Sendungen, die der Sender gelöscht hat. Cache: url -> [status, geprüft_am]."""
    items = [o for k in ("films", "crime", "docs") for o in cat[k]] + \
            [e for g in ("series", "filmSeries", "docSeries") for s in cat[g] for e in s["e"]]
    todo = {}
    for o in items:
        u = probe_url(o)
        c = cache.get(u)
        if c is None or NOW - c[1] > VALID_TTL or c[0] == 0:
            todo[u] = o
    checks = list(todo.items())[:MAX_CHECKS]
    with ThreadPoolExecutor(16) as pool:
        for (u, o), st in zip(checks, pool.map(lambda x: http_status(x[0]), checks)):
            cache[u] = [st, NOW]
    def ok(o):
        c = cache.get(probe_url(o))
        return c is None or not gone(o, c[0])
    removed = 0
    for k in ("films", "crime", "docs"):
        keep = [o for o in cat[k] if ok(o)]; removed += len(cat[k]) - len(keep); cat[k] = keep
    for g in ("series", "filmSeries", "docSeries"):
        for s in cat[g]:
            keep = [e for e in s["e"] if ok(e)]; removed += len(s["e"]) - len(keep); s["e"] = keep
        cat[g] = [s for s in cat[g] if s["e"]]
    live = {u: v for u, v in cache.items() if NOW - v[1] <= 14 * 24 * 3600}       # Cache klein halten
    cache.clear(); cache.update(live)
    print(f"Abrufbarkeit: {len(checks)} geprüft ({len(todo) - len(checks)} offen), {removed} gelöschte Sendungen entfernt",
          file=sys.stderr)

# ---------------------------------------------------------------- Live-TV

LIVE_ORDER = ["SRF 1", "SRF zwei", "SRF info", "Das Erste", "ZDF", "3sat", "ARTE", "ORF 1", "ORF 2", "ORF III",
              "ZDFneo", "ZDFinfo", "ONE", "tagesschau24", "phoenix", "ARD alpha", "ORF Sport"]
LIVE_NAMES = {"ARD": "Das Erste", "ARD Alpha": "ARD alpha", "ARD ONE": "ONE", "ARD Tagesschau": "tagesschau24",
              "ARTE.DE": "ARTE", "3Sat": "3sat", "ZDF.info": "ZDFinfo", "ZDF.neo": "ZDFneo", "PHOENIX": "phoenix",
              "ORF-1": "ORF 1", "ORF-2": "ORF 2", "ORF-3": "ORF III", "ORF-Sport": "ORF Sport",
              "WDR (Deutschland)": "WDR"}
LIVE_EXCLUDE = {"ARTE.FR", "DW", "KiKA"}

def live_channels(rows):
    """Livestreams aus der Filmliste (Rubrik 'Livestream') + SRF über die offizielle SRG-Schnittstelle.
    SRF-Streams brauchen eine Freigabe, die nur in der Schweiz ausgestellt wird – die holt die App beim Abspielen."""
    out = []
    for r in rows:
        if r["topic"] != "Livestream" or r["channel"] in LIVE_EXCLUDE or not r["url"]:
            continue
        name = re.sub(r"\s*Livestream\s*", " ", r["raw"]).strip()
        name = LIVE_NAMES.get(name, name)
        url = r["url"].replace("http://", "https://")
        o = {"n": name, "c": r["channel"], "u": url}
        if "/hls/de/" in url:
            o["alt"] = url.replace("/hls/de/", "/hls/int/")   # internationale Fassung (z.B. Das Erste)
        out.append(o)
    try:
        req = urllib.request.Request("https://il.srgssr.ch/integrationlayer/2.0/srf/mediaList/video/livestreams.json",
                                     headers={"User-Agent": "Mozilla/5.0"})
        for m in json.load(urllib.request.urlopen(req, timeout=30)).get("mediaList", []):
            name = m.get("channel", {}).get("title") or m.get("title", "").replace(" Live", "").replace(" live", "")
            out.append({"n": name, "c": "SRF", "srg": m["urn"]})
    except Exception as e:  # SRF nicht erreichbar: ohne SRF weitermachen
        print(f"SRF-Livestreams nicht abrufbar: {e}", file=sys.stderr)
    seen, uniq = set(), []
    for o in out:
        if o["n"] not in seen:
            seen.add(o["n"]); uniq.append(o)
    uniq.sort(key=lambda o: (LIVE_ORDER.index(o["n"]) if o["n"] in LIVE_ORDER else len(LIVE_ORDER), o["n"]))
    return uniq

# ---------------------------------------------------------------- Erstauftritt ("Neu")

DAY = 24 * 3600

def fs_key(o):
    """Stabiler Schlüssel einer Sendung über Läufe hinweg (URLs ändern sich gelegentlich)."""
    return f'{norm(o.get("t", ""))}|{norm(o.get("n", ""))}|{o.get("se")}|{o.get("ep")}'

def read_gz(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return json.load(f)

def load_previous(prev_dir):
    """Liest den vorherigen Katalog: Schlüssel -> Erstauftritt (None bei altem Katalog ohne 'fs')."""
    if not prev_dir or not os.path.exists(os.path.join(prev_dir, "index.json.gz")):
        return None
    idx = read_gz(os.path.join(prev_dir, "index.json.gz"))
    seen = {}
    def take(objs):
        for o in objs:
            k, v = fs_key(o), o.get("fs")
            old = seen.get(k, v)
            seen[k] = v if old is None else (old if v is None else min(old, v))
    for key in ("films", "crime", "docs"):
        take(idx.get(key, []))
    for path in glob.glob(os.path.join(prev_dir, "s", "*.json.gz")):
        try:
            take(read_gz(path).get("e", []))
        except (OSError, ValueError):
            pass
    print(f"Vorheriger Katalog: {len(seen)} Sendungen bekannt", file=sys.stderr)
    return {"generated": idx.get("generated", NOW), "seen": seen}

def assign_first_seen(cat, prev):
    def first_seen(o):
        ts = min(o.get("ts") or NOW, NOW)
        if prev is None:
            return ts                                     # allererster Lauf: Sendedatum als Näherung
        k = fs_key(o)
        if k in prev["seen"]:
            v = prev["seen"][k]
            return v if v is not None else min(ts, prev["generated"])  # Katalog ohne 'fs' (Umstellung)
        return NOW                                        # wirklich neu dazugekommen
    for key in ("films", "crime", "docs"):
        for o in cat[key]:
            o["fs"] = first_seen(o)
    for group in ("series", "filmSeries", "docSeries"):
        for s in cat[group]:
            for e in s["e"]:
                e["fs"] = first_seen(e)

def write(cat, out_dir):
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(os.path.join(out_dir, "s"))
    index = {k: cat[k] for k in ("version", "generated", "films", "crime", "docs", "live")}
    episodes_total = 0
    for group in ("series", "filmSeries", "docSeries"):
        heads = []
        for s in cat[group]:
            eps = s["e"]
            episodes_total += len(eps)
            write_gz(os.path.join(out_dir, "s", s["id"] + ".json.gz"), {"n": s["n"], "e": eps})
            recent = max(eps, key=lambda e: e["ts"])
            heads.append({
                "id": s["id"], "n": s["n"], "c": s["c"], "k": s["k"], **({"cr": 1} if s.get("cr") else {}),
                **({"im": s["im"]} if s.get("im") else {}),
                "cnt": len(eps), "new": s["new"],
                "d": (recent.get("d") or "")[:240],
                # Anzahl abspielbarer Folgen je Land – die App blendet Serien ohne Folgen aus
                "av": {c: sum(available(e, c) for e in eps) for c in ("DE", "AT", "CH")},
                # Neu: Erstauftritt der neuesten Folge und Anzahl Folgen der letzten 24 Stunden
                "nf": max(e.get("fs", 0) for e in eps),
                "nn": sum(e.get("fs", 0) >= NOW - DAY for e in eps),
            })
        index[group] = heads
    size = write_gz(os.path.join(out_dir, "index.json.gz"), index)
    return size, episodes_total

def main():
    global MAX_NEW_IMAGES
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", help="lokale Filmliste-akt.xz (sonst Download)")
    ap.add_argument("--previous", help="Ordner mit dem vorherigen Katalog (für 'Neu')")
    ap.add_argument("--corrections", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "corrections.json"),
                    help="Korrekturliste (Standard: tools/corrections.json)")
    ap.add_argument("--out", default="catalog")
    ap.add_argument("--no-images", action="store_true", help="keine Vorschaubilder suchen (schneller, zum Testen)")
    ap.add_argument("--no-check", action="store_true", help="nicht prüfen, ob die Sendungen noch abrufbar sind")
    ap.add_argument("--max-new-images", type=int, default=MAX_NEW_IMAGES)
    a = ap.parse_args()
    MAX_NEW_IMAGES = a.max_new_images
    t = time.time()
    rows = load(a.input)
    print(f"Filmliste: {len(rows)} Einträge", file=sys.stderr)
    live = live_channels(rows)
    cat = build(rows, load_corrections(a.corrections))
    cat["live"] = live
    assign_first_seen(cat, load_previous(a.previous))
    valid = load_valid_cache(a.previous)
    if not a.no_check: validate(cat, valid)
    images = {} if a.no_images else assign_images(cat, load_image_cache(a.previous))
    strip_internal(cat)
    size, eps = write(cat, a.out)
    if images:
        write_gz(os.path.join(a.out, "images.json.gz"), {**images, "_v": IMAGE_CACHE_VERSION})
    if valid:
        write_gz(os.path.join(a.out, "valid.json.gz"), valid)
    stats = {k: len(v) for k, v in cat.items() if isinstance(v, list)}
    print("Live: " + ", ".join(o["n"] for o in cat["live"]), file=sys.stderr)
    new = {k: sum(o["fs"] >= NOW - DAY for o in cat[k]) for k in ("films", "crime", "docs")}
    new["Folgen"] = sum(e["fs"] >= NOW - DAY for g in ("series", "filmSeries", "docSeries") for s in cat[g] for e in s["e"])
    print(f"Fertig in {time.time()-t:.0f}s: {stats}, {eps} Folgen; Übersicht {size/1e6:.1f} MB → {a.out}/", file=sys.stderr)
    print(f"Neu in den letzten 24 Stunden: {new}", file=sys.stderr)

if __name__ == "__main__":
    main()
