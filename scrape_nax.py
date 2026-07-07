import os
import json
import time
import requests

db_dir = os.path.join("database", "dramabox")
detail_dir = os.path.join(db_dir, "detail")
episodes_dir = os.path.join(db_dir, "allepisode")
os.makedirs(db_dir, exist_ok=True)
os.makedirs(detail_dir, exist_ok=True)
os.makedirs(episodes_dir, exist_ok=True)

targets = {
    "vip.json": "https://nax1.cc/api/dramabox/vip",
    "trending.json": "https://nax1.cc/api/dramabox/trending",
    "latest.json": "https://nax1.cc/api/dramabox/latest",
    "populersearch.json": "https://nax1.cc/api/dramabox/populersearch",
    "dubindo_terpopuler.json": "https://nax1.cc/api/dramabox/dubindo?classify=terpopuler",
    "dubindo_terbaru.json": "https://nax1.cc/api/dramabox/dubindo?classify=terbaru"
}

# Tambahkan foryou halaman 1-11
for p in range(1, 12):
    targets[f"foryou_{p}.json"] = f"https://nax1.cc/api/dramabox/foryou?page={p}"

headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

# 1. Unduh file beranda utama
for filename, url in targets.items():
    filepath = os.path.join(db_dir, filename)
    success = False
    for attempt in range(1, 4):
        try:
            print(f"Fetching: {url} (Attempt {attempt})")
            r = requests.get(url, headers=headers, timeout=30)
            if r.status_code == 200:
                data = r.json()
                with open(filepath, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                print(f"Saved: {filepath}")
                success = True
                break
            else:
                print(f"Failed with status: {r.status_code}")
        except Exception as e:
            print(f"Error: {e}")
        time.sleep(3)
    time.sleep(2)

# 2. Kumpulkan semua bookId unik dari file beranda yang diunduh
book_ids = set()

# Cari dari file JSON list biasa
for filename in os.listdir(db_dir):
    if filename.endswith(".json") and filename != "vip.json":
        filepath = os.path.join(db_dir, filename)
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    for b in data:
                        book_ids.add(b["bookId"])
        except Exception as e:
            print(f"Error reading {filename}: {e}")

# Cari dari vip.json (nested structure)
vip_path = os.path.join(db_dir, "vip.json")
if os.path.exists(vip_path):
    try:
        with open(vip_path, "r", encoding="utf-8") as f:
            data = json.load(f)
            columnVoList = data.get("columnVoList", [])
            for col in columnVoList:
                bookList = col.get("bookList", [])
                for b in bookList:
                    book_ids.add(b["bookId"])
    except Exception as e:
        print(f"Error reading vip.json: {e}")

print(f"Found {len(book_ids)} unique bookIds to fetch details/episodes.")

# 3. Unduh detail dan allepisode untuk masing-masing bookId
for idx, book_id in enumerate(sorted(book_ids)):
    # Fetch Detail
    detail_file = os.path.join(detail_dir, f"{book_id}.json")
    detail_url = f"https://nax1.cc/api/dramabox/detail?bookId={book_id}"
    
    # Fetch Episode
    episodes_file = os.path.join(episodes_dir, f"{book_id}.json")
    episodes_url = f"https://nax1.cc/api/dramabox/allepisode?bookId={book_id}"

    # Fetch Detail
    success_detail = False
    for attempt in range(1, 4):
        try:
            r = requests.get(detail_url, headers=headers, timeout=30)
            if r.status_code == 200:
                data = r.json()
                with open(detail_file, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                success_detail = True
                break
        except Exception as e:
            pass
        time.sleep(2)
    
    # Fetch Episode
    success_ep = False
    for attempt in range(1, 4):
        try:
            r = requests.get(episodes_url, headers=headers, timeout=30)
            if r.status_code == 200:
                data = r.json()
                with open(episodes_file, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                success_ep = True
                break
        except Exception as e:
            pass
        time.sleep(2)

    print(f"[{idx+1}/{len(book_ids)}] BookId {book_id} -> Detail: {success_detail}, Episodes: {success_ep}")
    time.sleep(1.5) # Delay sopan agar aman dari limit
