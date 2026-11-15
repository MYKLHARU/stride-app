"""Build Stride's offline UK product list from the Open Food Facts export.

Streams https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz,
keeps products sold in the UK with complete, believable nutrition per 100 g/ml, and writes:

  out/meta.json          counts and build date
  out/b/NN.json          products keyed by barcode, split by the barcode's last two digits
  out/names.json         compact search index: [barcode, name, brand, kcal, scans]

Data: Open Food Facts contributors, Open Database Licence (ODbL) 1.0.
Only the Python standard library is used.
"""
import csv, gzip, io, json, os, re, sys, time, urllib.request
from collections import Counter

URL = os.environ.get("OFF_URL", "https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz")
OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
csv.field_size_limit(1 << 30)

def num(v):
    if v is None: return None
    v = v.strip()
    if not v: return None
    try:
        x = float(v)
    except ValueError:
        return None
    return x if x == x else None  # drop NaN

def tidy_name(name, brand):
    name = re.sub(r"\s+", " ", (name or "").strip(" -,"))
    if not name: return ""
    if name.isupper() or name.islower():
        name = name[:1].upper() + name[1:].lower() if len(name) > 3 else name
    return name

def problems(n, drink, alcohol):
    """Reasons a product's numbers can't be right. Empty list means it passes."""
    out = []
    for k in ("p", "c", "s", "f", "sf", "fi"):
        if n[k] < 0 or n[k] > 100: out.append(f"{k} out of range")
    if n["salt"] < 0 or n["salt"] > 60: out.append("salt out of range")
    if n["kcal"] < 0 or n["kcal"] > 920: out.append("energy out of range")
    if n["sf"] > n["f"] + 0.5: out.append("saturates > fat")
    if n["s"] > n["c"] + 0.5: out.append("sugars > carbs")
    if n["p"] + n["c"] + n["f"] + n["fi"] > 105: out.append("over 100 g")
    est = 4 * n["p"] + 4 * n["c"] + 9 * n["f"] + 2 * n["fi"] + 7 * alcohol
    tol = max(15.0, 0.22 * max(n["kcal"], est))
    if abs(est - n["kcal"]) > tol and not (alcohol == 0 and drink and n["kcal"] > est):
        out.append("energy doesn't match macros")
    if n["kcal"] == 0 and est > 8: out.append("zero energy with macros")
    return out

def main():
    t0 = time.time()
    os.makedirs(os.path.join(OUT, "b"), exist_ok=True)
    req = urllib.request.Request(URL, headers={"User-Agent": "Stride-food-db/1.0 (personal app; github.com/MYKLHARU/stride-app)"})
    raw = urllib.request.urlopen(req, timeout=120)
    text = io.TextIOWrapper(gzip.GzipFile(fileobj=raw), encoding="utf-8", errors="replace", newline="")
    reader = csv.reader(text, delimiter="\t", quoting=csv.QUOTE_NONE)
    header = next(reader)
    col = {h: i for i, h in enumerate(header)}
    need = ["code", "product_name", "countries_tags", "proteins_100g", "carbohydrates_100g", "fat_100g"]
    missing = [h for h in need if h not in col]
    if missing: sys.exit(f"Export format changed, missing columns: {missing}")
    g = lambda row, h: row[col[h]] if h in col and col[h] < len(row) else ""

    stats, kept = Counter(), {}
    for row in reader:
        stats["rows"] += 1
        if stats["rows"] % 500000 == 0: print(f"{stats['rows']:,} rows, {len(kept):,} kept, {time.time() - t0:.0f}s", flush=True)
        countries = g(row, "countries_tags")
        if "en:united-kingdom" not in countries: continue
        stats["uk"] += 1
        code = re.sub(r"\D", "", g(row, "code"))
        if not (8 <= len(code) <= 14): stats["bad code"] += 1; continue
        per = g(row, "nutrition_data_per").strip().lower()
        if per and per not in ("100g", "100 g", "100ml"): stats["not per 100g"] += 1; continue
        name = tidy_name(g(row, "product_name_en") or g(row, "product_name"), g(row, "brands"))
        if not name or len(name) < 2: stats["no name"] += 1; continue
        kcal = num(g(row, "energy-kcal_100g"))
        if kcal is None:
            kj = num(g(row, "energy-kj_100g")) or num(g(row, "energy_100g"))
            kcal = kj / 4.184 if kj is not None else None
        p, c, f = num(g(row, "proteins_100g")), num(g(row, "carbohydrates_100g")), num(g(row, "fat_100g"))
        if None in (kcal, p, c, f): stats["incomplete nutrition"] += 1; continue
        n = {"kcal": kcal, "p": p, "c": c, "f": f,
             "s": num(g(row, "sugars_100g")) or 0.0, "sf": num(g(row, "saturated-fat_100g")) or 0.0,
             "fi": num(g(row, "fiber_100g")) or 0.0, "salt": num(g(row, "salt_100g"))}
        if n["salt"] is None:
            na = num(g(row, "sodium_100g")); n["salt"] = na * 2.5 if na is not None else 0.0
        alcohol = num(g(row, "alcohol_100g")) or 0.0
        qty = g(row, "quantity")
        drink = bool(re.search(r"\d\s*(ml|cl|l)\b", qty, re.I)) or "en:beverages" in g(row, "categories_tags")
        errs = g(row, "data_quality_errors_tags")
        if re.search(r"energy|nutrition|sugars|saturated|fat-value|carbohydrates|proteins|fiber|salt", errs): stats["flagged by OFF"] += 1; continue
        why = problems(n, drink, alcohol)
        if why: stats["failed checks"] += 1; continue
        brand = re.sub(r"\s+", " ", g(row, "brands").split(",")[0]).strip()
        scans = int(num(g(row, "unique_scans_n")) or 0)
        modified = int(num(g(row, "last_modified_t")) or 0)
        img = g(row, "image_small_url") or g(row, "image_front_small_url")
        m = re.search(r"/images/products/(.+?)\.\d+\.jpg$", img or "")
        img_key = m.group(1) if m else ""  # e.g. 500/015/702/4671/front_en.12 -> rebuilt in the app
        pq, sq = num(g(row, "product_quantity")), num(g(row, "serving_quantity"))
        rec = [name, brand, round(n["kcal"]), round(p, 1), round(c, 1), round(n["s"], 1), round(f, 1), round(n["sf"], 1), round(n["fi"], 1), round(n["salt"], 2),
               "ml" if drink and not re.search(r"\d\s*(g|kg)\b", qty, re.I) else "g", round(pq) if pq and 0 < pq < 20000 else 0,
               round(sq, 1) if sq and 0 < sq < 2000 else 0, img_key, round(alcohol, 1), scans, modified]
        old = kept.get(code)
        if old is None or modified > old[16]: kept[code] = rec

    shards = {}
    for code, rec in kept.items(): shards.setdefault(code[-2:], {})[code] = rec[:16]
    for k, v in shards.items():
        with open(os.path.join(OUT, "b", f"{k}.json"), "w", encoding="utf-8") as fh: json.dump(v, fh, ensure_ascii=False, separators=(",", ":"))
    names = sorted(([code, r[0], r[1], r[2], r[15]] for code, r in kept.items()), key=lambda x: -x[4])
    with open(os.path.join(OUT, "names.json"), "w", encoding="utf-8") as fh: json.dump(names, fh, ensure_ascii=False, separators=(",", ":"))
    meta = {"products": len(kept), "built": time.strftime("%Y-%m-%d"), "source": "Open Food Facts (ODbL)", "stats": dict(stats)}
    with open(os.path.join(OUT, "meta.json"), "w") as fh: json.dump(meta, fh, indent=1)
    print(json.dumps(meta, indent=1))
    print(f"done in {time.time() - t0:.0f}s")

if __name__ == "__main__":
    main()
