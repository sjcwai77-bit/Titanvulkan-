#!/usr/bin/env python3
"""Build a compact, attributed offline SQLite archive from the CC BY 4.0 FlightFinder compilation."""
import os, json, sqlite3, urllib.request, tempfile, pathlib, time
import pyarrow.parquet as pq

API = "https://huggingface.co/api/datasets/himaxym/aviation-safety-occurrences/parquet/default/train"
TARGET = pathlib.Path("app/src/main/assets/aviation.db")
TARGET.parent.mkdir(parents=True,exist_ok=True)
def fetch(url, dest=None):
    req=urllib.request.Request(url,headers={"User-Agent":"AviationArchive/0.2 (+github build; CC BY 4.0 attribution)"})
    with urllib.request.urlopen(req,timeout=180) as r:
        if dest:
            with open(dest,"wb") as out:
                while True:
                    x=r.read(1024*1024)
                    if not x:break
                    out.write(x)
            return
        return r.read()
urls=json.loads(fetch(API))
assert isinstance(urls,list) and len(urls)>0, "No dataset shards found"
print("Parquet shards:",len(urls),flush=True)
if TARGET.exists():TARGET.unlink()
db=sqlite3.connect(TARGET)
db.execute("PRAGMA journal_mode=OFF")
db.execute("PRAGMA synchronous=OFF")
db.execute("PRAGMA temp_store=MEMORY")
db.executescript("""
CREATE TABLE incidents (
 id INTEGER PRIMARY KEY,
 date TEXT, title TEXT, operator TEXT, manufacturer TEXT,
 aircraft TEXT, registration TEXT, country TEXT,
 category TEXT, severity TEXT, occupants INTEGER, fatalities INTEGER,
 summary TEXT, report_url TEXT, source TEXT, verified_extract INTEGER
);
CREATE VIRTUAL TABLE incidents_fts USING fts5(title,operator,aircraft,registration,country,summary,content='incidents',content_rowid='id');
CREATE INDEX idx_date ON incidents(date DESC);
CREATE INDEX idx_manufacturer ON incidents(manufacturer);
""")
def clean(v):
    if v is None:return ""
    return str(v).strip()
def manufacturer(ac):
    s=ac.lower()
    rules=[
    ("Boeing",["boeing","b-737","b-747","b-757","b-767","b-777","b-787"]),
    ("Airbus",["airbus","a320","a319","a321","a330","a340","a350","a380"]),
    ("McDonnell Douglas",["mcdonnell","md-80","md-82","md-83","md-88","md-90","md-11","dc-9","dc-10"]),
    ("Douglas",["douglas","dc-3","dc-4","dc-6","dc-7"]),
    ("Lockheed",["lockheed","l-1011","constellation"]),
    ("Embraer",["embraer"]),
    ("Bombardier",["bombardier","canadair","crj"]),
    ("Cessna",["cessna"]),
    ("Piper",["piper"]),
    ("Beechcraft",["beechcraft","beech "]),
    ("Antonov",["antonov","an-12","an-24","an-26","an-2 "]),
    ("Tupolev",["tupolev","tu-134","tu-154","tu-204"]),
    ("Ilyushin",["ilyushin","il-62","il-76","il-86"]),
    ("ATR",["atr 42","atr 72","atr-42","atr-72"]),
    ("de Havilland",["de havilland","dhc-","dash 8"]),
    ("Fokker",["fokker"]),
    ("Sukhoi",["sukhoi","superjet"]),
    ("Saab",["saab"]),
    ("Pilatus",["pilatus"]),
    ]
    for brand,names in rules:
        if any(t in s for t in names):return brand
    return "Other / unidentified"
n=0
with db:
 for shard_idx,url in enumerate(urls):
    p=pathlib.Path(tempfile.gettempdir())/("aviation_arch_%d.parquet"%shard_idx)
    fetch(url,p)
    pf=pq.ParquetFile(p)
    for batch in pf.iter_batches(batch_size=3000):
     for d in batch.to_pylist():
      date=clean(d.get("event_date"))
      ac=clean(d.get("type_full")) or clean(d.get("type_icao")) or "Aircraft type unknown"
      operator=clean(d.get("operator")) or clean(d.get("airline_name_canonical"))
      reg=clean(d.get("registration"))
      country=clean(d.get("country_iso"))
      cause=clean(d.get("cause_summary"))
      report=clean(d.get("report_url"))
      source=clean(d.get("primary_source"))
      severity=clean(d.get("severity"))
      occ=d.get("occupants");fat=d.get("fatalities_total")
      title=(operator+" — "+ac) if operator else ac
      db.execute("""INSERT INTO incidents(date,title,operator,manufacturer,aircraft,registration,country,category,severity,occupants,fatalities,summary,report_url,source,verified_extract)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",(date,title,operator,manufacturer(ac),ac,reg,country,clean(d.get("category")) or clean(d.get("occurrence_type")),severity,occ,fat,cause,report,source,0 if d.get("llm_enriched") else 1))
      n+=1
    print("Imported shard",shard_idx+1,"records",n,flush=True)
    p.unlink()
 assert n>200000,"Unexpectedly small archive: "+str(n)
 # Add a few recognisable, reviewed case entries with flight numbers, while leaving the international corpus intact.
 curated=[
 ("1995-12-20","American Airlines Flight 965","American Airlines","Boeing","Boeing 757-223","N651AA","CO","Controlled flight into terrain","fatal",163,159,"Night approach to Cali. The crew selected an incorrect FMS waypoint and descended into mountainous terrain. Four survivors.","https://www.faa.gov/lessons_learned/transport_airplane/accidents/N651AA","FAA Lessons Learned",1),
 ("2010-11-04","Qantas Flight 32","Qantas","Airbus","Airbus A380-842","VH-OQA","SG","Uncontained engine failure","serious_incident",469,0,"An uncontained engine failure damaged several aircraft systems. The crew returned to Singapore and all survived.","https://www.atsb.gov.au/investigations/ao-2010-089","ATSB",1),
 ("2009-01-15","US Airways Flight 1549","US Airways","Airbus","Airbus A320-214","N106US","US","Bird strike / ditching","incident",155,0,"After a bird encounter and loss of engine thrust, the crew ditched in the Hudson River. All 155 survived.","https://www.ntsb.gov/investigations/Pages/DCA09MA026.aspx","NTSB",1)]
 db.executemany("""INSERT INTO incidents(date,title,operator,manufacturer,aircraft,registration,country,category,severity,occupants,fatalities,summary,report_url,source,verified_extract) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",curated)
 db.execute("INSERT INTO incidents_fts(incidents_fts) VALUES ('rebuild')")
db.execute("PRAGMA optimize")
db.close()
print("FINAL offline cases",n+len(curated),"DB size MB",round(TARGET.stat().st_size/1e6,2),flush=True)
