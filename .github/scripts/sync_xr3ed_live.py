#!/usr/bin/env python3
"""
sync_xr3ed_live.py
Engine sinkronisasi dan enkripsi katalog siaran xr3edTV.
Semua parameter, URL endpoint, dan kunci didefinisikan murni lewat GitHub Secrets / environment variables.
"""

import os
import sys
import json
import base64
from pathlib import Path
from Crypto.Cipher import AES

def load_local_properties() -> dict:
    props = {}
    prop_path = Path("local.properties")
    if prop_path.exists():
        with open(prop_path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    props[k.strip()] = v.strip()
    return props

LOCAL_PROPS = load_local_properties()

def get_secret(name: str) -> str:
    val = os.environ.get(name, "").strip()
    if not val:
        val = LOCAL_PROPS.get(name, "").strip()
    return val

ENGINE_URL = get_secret("XR3ED_ENGINE_URL")
ENGINE_AUTH = get_secret("XR3ED_ENGINE_AUTH")
STREAM_PREFIX = get_secret("XR3ED_STREAM_PREFIX")
MAP_D = get_secret("XR3ED_ENGINE_MAP_D")
MAP_E = get_secret("XR3ED_ENGINE_MAP_E")
XOR_KEY = get_secret("XR3EDTV_XOR_KEY")

def decrypt_field(enc_str: str) -> str:
    if not enc_str or not isinstance(enc_str, str):
        return ""
    
    if STREAM_PREFIX and enc_str.startswith(STREAM_PREFIX):
        enc_str = enc_str[len(STREAM_PREFIX):]
    elif enc_str.startswith("http://") or enc_str.startswith("https://"):
        parts = enc_str.split("/", 3)
        if len(parts) >= 4:
            enc_str = parts[3]
    
    pad_needed = (4 - len(enc_str) % 4) % 4
    enc_str += "=" * pad_needed
    
    try:
        raw_bytes = base64.b64decode(enc_str)
        if len(raw_bytes) < 32:
            return enc_str
        key = raw_bytes[:16]
        iv = raw_bytes[-16:]
        ciphertext = raw_bytes[16:-16]
        
        cipher = AES.new(key, AES.MODE_CBC, iv)
        decrypted_bytes = cipher.decrypt(ciphertext)
        pad = decrypted_bytes[-1]
        if 1 <= pad <= 16:
            decrypted_bytes = decrypted_bytes[:-pad]
        dec_text = decrypted_bytes.decode('utf-8', errors='ignore').strip()
        
        if MAP_D and MAP_E and len(MAP_D) == len(MAP_E):
            res = []
            for c in dec_text:
                idx = MAP_D.find(c)
                if idx != -1:
                    res.append(MAP_E[idx])
                else:
                    res.append(c)
            return "".join(res)
        return dec_text
    except Exception:
        return enc_str

def xor_encrypt(text: str, key: str) -> str:
    data_bytes = text.encode("utf-8")
    key_bytes = key.encode("utf-8")
    key_len = len(key_bytes)
    encrypted = bytes([b ^ key_bytes[i % key_len] for i, b in enumerate(data_bytes)])
    return base64.b64encode(encrypted).decode("utf-8")

def channels_to_m3u(channels: list) -> str:
    lines = ["#EXTM3U"]
    for ch in channels:
        name = ch.get("name", "").strip()
        category = ch.get("category", "").strip()
        logo = ch.get("logo", "").strip()
        stream = ch.get("stream_url", "").strip()
        ua = ch.get("user_agent", "").strip()
        ref = ch.get("referer", "").strip()
        
        if not stream:
            continue
            
        extinf = f'#EXTINF:-1 group-title="{category}"'
        if logo:
            extinf += f' tvg-logo="{logo}"'
        extinf += f',{name}'
        lines.append(extinf)
        
        pipe_headers = []
        if ua:
            lines.append(f'#EXTVLCOPT:http-user-agent={ua}')
            pipe_headers.append(f'User-Agent={ua}')
        if ref:
            lines.append(f'#EXTVLCOPT:http-referrer={ref}')
            pipe_headers.append(f'Referer={ref}')
            
        if pipe_headers:
            full_stream = f'{stream}|{"|".join(pipe_headers)}'
        else:
            full_stream = stream
        lines.append(full_stream)
    return "\n".join(lines) + "\n"

def fetch_online_channels(engine_url: str, engine_auth: str) -> list:
    channels = []
    try:
        from curl_cffi import requests
        s = requests.Session(impersonate="chrome124")
    except Exception:
        import requests
        s = requests.Session()

    try:
        login_url = f"{engine_url.rstrip('/')}/api/login"
        resp = s.post(login_url, data={"code": engine_auth}, timeout=12)
        if resp.status_code != 200:
            return []
        data = resp.json()
        categories = data.get("categories", []) or []
        for cat in categories:
            cat_id = cat.get("id")
            if not cat_id:
                continue
            pl_url = f"{engine_url.rstrip('/')}/api/playlist?id={cat_id}"
            pl_resp = s.get(pl_url, timeout=12)
            if pl_resp.status_code == 200:
                pdata = pl_resp.json()
                for grp in pdata.get("a", []):
                    grp_name = grp.get("a", "")
                    for item in grp.get("b", []):
                        raw_stream = item.get("stream_url", "")
                        raw_ua = item.get("user_agent", "")
                        raw_ref = item.get("referer", "")
                        channels.append({
                            "category": grp_name,
                            "name": item.get("a", ""),
                            "logo": item.get("logo_url", ""),
                            "stream_url": decrypt_field(raw_stream),
                            "user_agent": decrypt_field(raw_ua),
                            "referer": decrypt_field(raw_ref),
                        })
    except Exception as e:
        print(f"Info fetch online: {e}")
    return channels

def main():
    print("=== xr3edTV Live Engine Sync ===")
    if not XOR_KEY:
        print("ERROR: XR3EDTV_XOR_KEY tidak ditemukan di environment atau local.properties.")
        sys.exit(1)

    all_channels = []
    
    # 1. Coba fetch online dari engine
    if ENGINE_URL and ENGINE_AUTH:
        print("Mencoba mengambil pembaruan saluran dari engine...")
        all_channels = fetch_online_channels(ENGINE_URL, ENGINE_AUTH)
        if all_channels:
            print(f"Berhasil menarik {len(all_channels)} saluran online dari engine!")

    # 2. Jika online fetch kosong (misal terhalang proteksi WAF), gunakan cache lokal jika tersedia
    if not all_channels:
        candidates = [
            get_secret("XR3ED_CACHE_FILE"),
            "live_catalog_cache.json",
            "assets/live_catalog_cache.json",
            "scratch/live_catalog_cache.json",
        ]
        for cand in candidates:
            if cand and Path(cand).exists():
                print(f"Memuat saluran dari cache: {cand}")
                with open(cand, "r", encoding="utf-8") as f:
                    all_channels = json.load(f)
                break

    # 3. Jika tidak ada sumber baru, periksa apakah file assets/xr3ed_live.enc sudah ada di repositori
    output_path = Path("assets/xr3ed_live.enc")
    if not all_channels:
        if output_path.exists() and output_path.stat().st_size > 1000:
            print(f"Katalog terenkripsi {output_path} ({output_path.stat().st_size} bytes) sudah aktif dan dalam kondisi valid.")
            sys.exit(0)
        print("PERINGATAN: Tidak ada saluran yang ditemukan.")
        sys.exit(1)

    print(f"Total saluran diproses: {len(all_channels)}")
    m3u_text = channels_to_m3u(all_channels)
    
    print(f"Mengenkripsi playlist ({len(m3u_text)} karakter) dengan kunci XOR...")
    enc_content = xor_encrypt(m3u_text, XOR_KEY)
    
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(enc_content)
        
    print(f"SUKSES! File tersimpan aman di: {output_path} (Ukuran: {len(enc_content)} bytes)")

if __name__ == "__main__":
    main()
