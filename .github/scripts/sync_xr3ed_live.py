#!/usr/bin/env python3
"""
sync_xr3ed_live.py
Engine sinkronisasi dan enkripsi katalog siaran xr3edTV.
Semua parameter, URL endpoint, dan kunci didefinisikan murni lewat GitHub Secrets / environment variables.
"""

import os
import sys
import re
import json
import base64
import uuid
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
WORKER_URL = get_secret("XR3EDTV_WORKER_URL")
WORKER_TOKEN = get_secret("XR3EDTV_WORKER_TOKEN")
ENGINE_AUTH = get_secret("XR3ED_ENGINE_AUTH")
DEVICE_ID = get_secret("XR3ED_DEVICE_ID")
STREAM_PREFIX = get_secret("XR3ED_STREAM_PREFIX")
MAP_D = get_secret("XR3ED_ENGINE_MAP_D")
MAP_E = get_secret("XR3ED_ENGINE_MAP_E")
XOR_KEY = get_secret("XR3EDTV_XOR_KEY")

def generate_auth_header(code: str) -> str:
    if not code:
        return ""
    try:
        import bcrypt
        salt = bcrypt.gensalt(rounds=10, prefix=b'2a')
        h = bcrypt.hashpw(code.encode("utf-8"), salt).decode("utf-8")
        return f"Basic {h}"
    except Exception:
        return ""

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

def fetch_online_channels(engine_url: str, engine_auth: str, device_id: str, worker_url: str = "", worker_token: str = "") -> list:
    channels = []
    base_url = worker_url.rstrip("/") if worker_url else (engine_url.rstrip("/") if engine_url else "")
    if not base_url or not engine_auth:
        return channels

    try:
        from curl_cffi import requests
        s = requests.Session(impersonate="chrome124")
    except Exception:
        import requests
        s = requests.Session()

    if not device_id:
        device_id = str(uuid.uuid4())

    headers = {
        "User-Agent": "KingTV/1.3",
        "X-Version": "335",
        "X-Device": device_id,
        "Accept-Charset": "UTF-8",
        "X-Model": "Pixel 6",
        "X-System": "Android 14",
        "X-Android": "34"
    }

    if worker_token:
        headers["X-Custom-Token"] = worker_token

    auth_hdr = generate_auth_header(engine_auth)
    if auth_hdr:
        headers["X-Authorization"] = auth_hdr

    try:
        login_url = f"{base_url}/api/login"
        resp = s.post(login_url, data={"code": engine_auth}, headers=headers, timeout=15)
        if resp.status_code != 200:
            print(f"Peringatan: Login engine gagal (HTTP {resp.status_code}): {resp.text[:300]}")
            return []
            
        res_json = resp.json()
        if isinstance(res_json, dict) and not res_json.get("statusCode", True):
            print(f"Peringatan: Respons login ditolak: {res_json.get('message')}")
            return []

        data_obj = res_json.get("data", {}) if isinstance(res_json, dict) else {}
        playlists = data_obj.get("playlist", []) if isinstance(data_obj, dict) else []
        if not playlists and isinstance(res_json, dict):
            playlists = res_json.get("categories", []) or []

        for item in playlists:
            cat_id = item.get("sortn") or item.get("id") or item.get("code")
            cat_name = item.get("name") or "Live"
            if not cat_id:
                continue

            pl_url = f"{base_url}/api/playlist?id={cat_id}"
            pl_resp = s.get(pl_url, headers=headers, timeout=15)
            if pl_resp.status_code != 200:
                continue

            pl_text = pl_resp.text.strip()
            if pl_text.startswith("#EXTM3U") or pl_text.startswith("#EXTINF"):
                cur_info = {}
                for line in pl_text.splitlines():
                    line = line.strip()
                    if not line:
                        continue
                    if line.startswith("#EXTINF:"):
                        ch_name = ""
                        if "," in line:
                            ch_name = line.split(",", 1)[1].strip()
                        group = cat_name
                        m_grp = re.search(r'group-title="([^"]+)"', line)
                        if m_grp:
                            group = m_grp.group(1).strip()
                        logo = ""
                        m_logo = re.search(r'tvg-logo="([^"]+)"', line)
                        if m_logo:
                            logo = m_logo.group(1).strip()
                        cur_info = {
                            "category": group,
                            "name": ch_name,
                            "logo": logo,
                            "user_agent": "",
                            "referer": ""
                        }
                    elif line.startswith("#EXTVLCOPT:http-user-agent="):
                        cur_info["user_agent"] = decrypt_field(line.split("=", 1)[1].strip())
                    elif line.startswith("#EXTVLCOPT:http-referrer="):
                        cur_info["referer"] = decrypt_field(line.split("=", 1)[1].strip())
                    elif line.startswith("http://") or line.startswith("https://"):
                        if cur_info:
                            cur_info["stream_url"] = decrypt_field(line)
                            channels.append(cur_info)
                            cur_info = {}
            else:
                try:
                    pdata = pl_resp.json()
                    for grp in pdata.get("a", []):
                        grp_name = grp.get("a", "")
                        for subitem in grp.get("b", []):
                            raw_stream = subitem.get("stream_url", "")
                            raw_ua = subitem.get("user_agent", "")
                            raw_ref = subitem.get("referer", "")
                            channels.append({
                                "category": grp_name,
                                "name": subitem.get("a", ""),
                                "logo": subitem.get("logo_url", ""),
                                "stream_url": decrypt_field(raw_stream),
                                "user_agent": decrypt_field(raw_ua),
                                "referer": decrypt_field(raw_ref),
                            })
                except Exception:
                    pass
    except Exception as e:
        print(f"Info fetch online: {e}")
    return channels

def main():
    print("=== xr3edTV Live Engine Sync ===")
    if not XOR_KEY:
        print("ERROR: XR3EDTV_XOR_KEY tidak ditemukan di environment atau local.properties.")
        sys.exit(1)

    all_channels = []
    
    # 1. Coba fetch online dari worker atau engine langsung
    if (WORKER_URL or ENGINE_URL) and ENGINE_AUTH:
        target_name = "Cloudflare Worker" if WORKER_URL else "Engine langsung"
        print(f"Mencoba mengambil pembaruan saluran dari {target_name}...")
        all_channels = fetch_online_channels(ENGINE_URL, ENGINE_AUTH, DEVICE_ID, WORKER_URL, WORKER_TOKEN)
        if all_channels:
            print(f"Berhasil menarik {len(all_channels)} saluran online dari {target_name}!")

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
