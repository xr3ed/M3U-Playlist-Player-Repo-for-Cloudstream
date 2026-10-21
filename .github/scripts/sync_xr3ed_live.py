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

def main():
    print("=== xr3edTV Live Engine Sync ===")
    if not XOR_KEY:
        print("ERROR: XR3EDTV_XOR_KEY tidak ditemukan di environment atau local.properties.")
        sys.exit(1)

    all_channels = []
    
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

    if not all_channels:
        print("PERINGATAN: Tidak ada cache saluran yang ditemukan.")
        sys.exit(1)

    print(f"Total saluran diproses: {len(all_channels)}")
    m3u_text = channels_to_m3u(all_channels)
    
    print(f"Mengenkripsi playlist ({len(m3u_text)} karakter) dengan kunci XOR...")
    enc_content = xor_encrypt(m3u_text, XOR_KEY)
    
    output_path = Path("assets/xr3ed_live.enc")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(enc_content)
        
    print(f"SUKSES! File tersimpan aman di: {output_path} (Ukuran: {len(enc_content)} bytes)")

if __name__ == "__main__":
    main()
