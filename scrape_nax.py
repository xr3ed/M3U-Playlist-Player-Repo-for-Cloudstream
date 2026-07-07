import os
import json
import time
import requests

db_dir = os.path.join("database", "dramabox")
os.makedirs(db_dir, exist_ok=True)

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

for filename, url in targets.items():
    filepath = os.path.join(db_dir, filename)
    success = False
    for attempt in range(1, 4):
        try:
            print(f"Fetching: {url} (Attempt {attempt})")
            r = requests.get(url, headers=headers, timeout=30)
            if r.status_code == 200:
                # Validasi JSON
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
        time.sleep(3) # Delay antar percobaan
    
    if not success:
        print(f"CRITICAL: Failed to update {filename}")
    
    time.sleep(2) # Delay agar tidak terkena rate limit
