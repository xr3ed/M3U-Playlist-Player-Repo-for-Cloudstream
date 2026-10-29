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
import urllib.request
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
XR3EV_PLAYER_URL = get_secret("XR3EV_PLAYER_URL")
XR3EV_PASSWORD = get_secret("XR3EV_PASSWORD")
XR3EV_SALT = get_secret("XR3EV_SALT")
APIPLAYER_BASE_URL = get_secret("APIPLAYER_BASE_URL")
APIPLAYER_UA = get_secret("APIPLAYER_UA")
APIPLAYER_REFERER = get_secret("APIPLAYER_REFERER")
APIPLAYER_TOKEN = get_secret("APIPLAYER_TOKEN")
APIPLAYER_CATALOG_DATA = get_secret("APIPLAYER_CATALOG_DATA")
APIPLAYER_CACHE_FILE = get_secret("APIPLAYER_CACHE_FILE")

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
        kodi_props = ch.get("kodi_props", {})
        
        if not stream:
            continue
            
        extinf = f'#EXTINF:-1 group-title="{category}"'
        if logo:
            extinf += f' tvg-logo="{logo}"'
        extinf += f',{name}'
        lines.append(extinf)
        
        if kodi_props:
            for pk, pv in kodi_props.items():
                lines.append(f'#KODIPROP:{pk}={pv}')
        
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

def load_existing_enc_channels(enc_path: Path, xor_key: str) -> list:
    if not enc_path.exists() or not xor_key:
        return []
    try:
        with open(enc_path, "r", encoding="utf-8") as f:
            enc_data = f.read().strip()
        raw = base64.b64decode(enc_data)
        key_bytes = xor_key.encode("utf-8")
        dec_m3u = bytes([b ^ key_bytes[i % len(key_bytes)] for i, b in enumerate(raw)]).decode("utf-8", errors="ignore")

        channels = []
        cur_info = {}
        for line in dec_m3u.splitlines():
            line = line.strip()
            if not line:
                continue
            if line.startswith("#EXTINF:"):
                ch_name = ""
                if "," in line:
                    ch_name = line.split(",", 1)[1].strip()
                group = "Live"
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
                    "referer": "",
                    "kodi_props": {}
                }
            elif line.startswith("#KODIPROP:"):
                prop = line.split(":", 1)[1].strip()
                if "=" in prop:
                    pk, pv = prop.split("=", 1)
                    if "kodi_props" not in cur_info:
                        cur_info["kodi_props"] = {}
                    cur_info["kodi_props"][pk.strip()] = pv.strip()
            elif line.startswith("#EXTVLCOPT:http-user-agent="):
                cur_info["user_agent"] = line.split("=", 1)[1].strip()
            elif line.startswith("#EXTVLCOPT:http-referrer="):
                cur_info["referer"] = line.split("=", 1)[1].strip()
            elif line.startswith("http://") or line.startswith("https://"):
                if cur_info:
                    parts = line.split("|")
                    cur_info["stream_url"] = parts[0]
                    for p in parts[1:]:
                        if p.startswith("User-Agent="):
                            cur_info["user_agent"] = p.split("=", 1)[1]
                        elif p.startswith("Referer="):
                            cur_info["referer"] = p.split("=", 1)[1]
                    channels.append(cur_info)
                    cur_info = {}
        return channels
    except Exception as e:
        print(f"Info load existing enc: {e}")
        return []

def fetch_apiplayer_channels(catalog_data_b64: str, cache_file: str = "") -> list:
    channels = []
    if catalog_data_b64:
        try:
            import gzip
            raw_gz = base64.b64decode(catalog_data_b64)
            decomp = gzip.decompress(raw_gz).decode("utf-8")
            data = json.loads(decomp)
            if isinstance(data, list):
                channels = data
        except Exception as e:
            print(f"Info decode API Player catalog: {e}")

    if not channels and cache_file:
        c_path = Path(cache_file)
        if c_path.exists():
            try:
                with open(c_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list):
                        channels = data
            except Exception as e:
                print(f"Info read API Player cache {cache_file}: {e}")
    return channels

def fetch_xr3ev_channels(player_url: str, pw: str, salt: str) -> list:
    if not player_url or not pw or not salt:
        return []
    
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
        from cryptography.hazmat.primitives import hashes
    except ImportError:
        print("Peringatan: cryptography library belum terpasang, melewati fetch XR3EV.")
        return []

    # 1. Discover active worker endpoints dynamically from player
    worker_urls = []
    try:
        bitmovin_page = f"{player_url.rstrip('/')}/bitmovin"
        req = urllib.request.Request(bitmovin_page, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=10) as r:
            html = r.read().decode("utf-8")

        chunk_paths = re.findall(r'/_next/static/chunks/[^\s"\'<>]+', html)
        webpack_chunk = next((c for c in chunk_paths if "webpack-" in c), "")
        chunks_to_check = list(chunk_paths)

        if webpack_chunk:
            wp_url = f"{player_url.rstrip('/')}{webpack_chunk}"
            req_wp = urllib.request.Request(wp_url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req_wp, timeout=10) as r:
                wp_txt = r.read().decode("utf-8")
            m_957 = re.search(r'957:\s*"([a-f0-9]+)"', wp_txt)
            if m_957:
                hash_957 = m_957.group(1)
                chunks_to_check.append(f"/_next/static/chunks/957.{hash_957}.js")

        for c_path in chunks_to_check:
            c_url = f"{player_url.rstrip('/')}{c_path}"
            try:
                req_c = urllib.request.Request(c_url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req_c, timeout=10) as r:
                    c_txt = r.read().decode("utf-8")
                found_workers = re.findall(r'https?://[a-zA-Z0-9_\.-]*(?:workers\.dev|bitmovin\.[a-zA-Z0-9_\.-]+)/?', c_txt)
                for w in found_workers:
                    w_clean = w.split("?")[0].rstrip("/") + "/"
                    if w_clean not in worker_urls:
                        worker_urls.append(w_clean)
            except Exception:
                continue
    except Exception as e:
        print(f"Info discover player worker: {e}")

    if not worker_urls:
        return []

    # 2. Derive Key
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt.encode("utf-8"),
        iterations=1000,
    )
    key = kdf.derive(pw.encode("utf-8"))
    aesgcm = AESGCM(key)

    # 3. Target channels: HBO suite & Cinemax
    targets = [
        ("hbocub", "HBO"),
        ("hbosigcub", "HBO Signature"),
        ("hbohitscub", "HBO Hits"),
        ("hbofamcub", "HBO Family"),
        ("cinemaxcub", "Cinemax"),
    ]

    extracted = []
    for ch_id, ch_name in targets:
        for w_url in worker_urls:
            url = f"{w_url}?id={ch_id}&t=1"
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=7) as r:
                    raw = r.read().decode("utf-8").strip()
                data = json.loads(raw)
                iv = base64.b64decode(data["iv"])
                enc_bytes = base64.b64decode(data["data"])
                dec_json = json.loads(aesgcm.decrypt(iv, enc_bytes, None).decode("utf-8"))
                
                dash_url = dec_json.get("dash", "").strip()
                drm_key = dec_json.get("drm", "").strip()
                
                if dash_url and drm_key:
                    # Normalisasi domain CDN Transvision jika DNS host relay mati
                    dash_url = dash_url.replace("cdnjktftnt1uk913.transvision.co.id", "cdnjkt913.transvision.co.id")
                    extracted.append({
                        "category": "MOVIES ID",
                        "name": ch_name,
                        "logo": "",
                        "stream_url": dash_url,
                        "kodi_props": {
                            "inputstream.adaptive.license_type": "org.w3.clearkey",
                            "inputstream.adaptive.license_key": drm_key
                        }
                    })
                    print(f"Berhasil fetch siaran aktif: {ch_name} -> {dash_url}")
                    break
            except Exception:
                continue
    return extracted

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
                            "referer": "",
                            "kodi_props": {}
                        }
                    elif line.startswith("#KODIPROP:"):
                        prop = line.split(":", 1)[1].strip()
                        if "=" in prop:
                            pk, pv = prop.split("=", 1)
                            if "kodi_props" not in cur_info:
                                cur_info["kodi_props"] = {}
                            cur_info["kodi_props"][pk.strip()] = pv.strip()
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
                    raw_text = pl_text.strip()
                    if not (raw_text.startswith("{") or raw_text.startswith("[")):
                        try:
                            pad = (4 - len(raw_text) % 4) % 4
                            raw_bytes = base64.b64decode(raw_text + "=" * pad)
                            raw_text = raw_bytes.decode("utf-8", errors="ignore").strip()
                        except Exception:
                            pass

                    pdata = json.loads(raw_text)
                    drm_licenses_map = {}
                    for lic in pdata.get("drm_licenses", []) if isinstance(pdata, dict) else []:
                        d_id = str(lic.get("drm_id", "")).strip()
                        d_type = lic.get("drm_type", "").strip()
                        raw_key = lic.get("drm_key", "")
                        dec_key = decrypt_field(raw_key) if raw_key else ""
                        if d_id and dec_key:
                            drm_licenses_map[d_id] = (d_type, dec_key)

                    groups = pdata.get("a", []) if isinstance(pdata, dict) else []
                    if not groups and isinstance(pdata, list):
                        groups = pdata
                    for grp in groups:
                        grp_name = grp.get("a", "") or grp.get("name", "") or cat_name
                        subitems = grp.get("b", []) or grp.get("channels", [])
                        for subitem in subitems:
                            raw_stream = subitem.get("stream_url", "") or subitem.get("url", "")
                            raw_ua = subitem.get("user_agent", "") or subitem.get("ua", "")
                            raw_ref = subitem.get("referer", "") or subitem.get("ref", "")
                            ch_name = subitem.get("a", "") or subitem.get("name", "")
                            logo = subitem.get("logo_url", "") or subitem.get("logo", "")
                            sub_drm_id = str(subitem.get("drm_id", "")).strip()
                            kodi_props = {}
                            if sub_drm_id and sub_drm_id in drm_licenses_map:
                                d_type, d_key = drm_licenses_map[sub_drm_id]
                                lic_type = "org.w3.clearkey" if "clearkey" in d_type.lower() else d_type
                                kodi_props = {
                                    "inputstream.adaptive.license_type": lic_type,
                                    "inputstream.adaptive.license_key": d_key
                                }
                            if ch_name and raw_stream:
                                channels.append({
                                    "category": grp_name,
                                    "name": ch_name,
                                    "logo": logo,
                                    "stream_url": decrypt_field(raw_stream),
                                    "user_agent": decrypt_field(raw_ua),
                                    "referer": decrypt_field(raw_ref),
                                    "kodi_props": kodi_props
                                })
                except Exception as ex:
                    print(f"Info parse playlist {cat_id} ({cat_name}): {ex}")
    except Exception as e:
        print(f"Info fetch online: {e}")
    return channels

def main():
    print("=== xr3edTV Live Engine Sync ===")
    if not XOR_KEY:
        print("ERROR: XR3EDTV_XOR_KEY tidak ditemukan di environment atau local.properties.")
        sys.exit(1)

    all_channels = []
    output_path = Path("assets/xr3ed_live.enc")
    
    # 1. Fetch online dari worker atau engine langsung
    if (WORKER_URL or ENGINE_URL) and ENGINE_AUTH:
        target_name = "Cloudflare Worker" if WORKER_URL else "Engine langsung"
        print(f"Mencoba mengambil pembaruan saluran dari {target_name}...")
        all_channels = fetch_online_channels(ENGINE_URL, ENGINE_AUTH, DEVICE_ID, WORKER_URL, WORKER_TOKEN)
        if all_channels:
            print(f"Berhasil menarik {len(all_channels)} saluran online dari {target_name}!")

    # 2. Jika online fetch kosong (misal terhalang proteksi WAF), muat saluran dari cache atau file .enc yang sudah ada
    candidates = [
        get_secret("XR3ED_CACHE_FILE"),
        "assets/live_catalog_cache.json",
        "live_catalog_cache.json",
        "scratch/live_catalog_cache.json",
    ]
    for cand in candidates:
        if cand and Path(cand).exists():
            try:
                print(f"Memuat saluran dari cache: {cand}")
                with open(cand, "r", encoding="utf-8") as f:
                    cached = json.load(f)
                    if cached and len(cached) > len(all_channels):
                        all_channels = cached
                        break
            except Exception as e:
                print(f"Info read cache {cand}: {e}")

    existing = load_existing_enc_channels(output_path, XOR_KEY)
    if not all_channels:
        if existing:
            print(f"Memuat {len(existing)} saluran dasar dari {output_path}...")
            all_channels = existing
    else:
        if existing:
            print(f"Menggabungkan {len(existing)} saluran lokal terverifikasi dari {output_path}...")
            seen_streams = {c.get("stream_url") for c in all_channels}
            for e_ch in existing:
                if e_ch.get("stream_url") not in seen_streams:
                    all_channels.append(e_ch)
                    seen_streams.add(e_ch.get("stream_url"))

    # 4. Fetch saluran aktif XR3EV (HBO suite & Cinemax dengan ClearKey dinamis)
    if XR3EV_PLAYER_URL and XR3EV_PASSWORD and XR3EV_SALT:
        print("Mengambil siaran film aktif & ClearKey DRM dari XR3EV...")
        xr3ev_chs = fetch_xr3ev_channels(XR3EV_PLAYER_URL, XR3EV_PASSWORD, XR3EV_SALT)
        if xr3ev_chs:
            print(f"Berhasil menambahkan {len(xr3ev_chs)} saluran film terverifikasi dengan ClearKey DRM!")
            # Tambahkan stream XR3EV ke dalam daftar siaran
            seen_streams = {c.get("stream_url") for c in all_channels}
            for x_ch in xr3ev_chs:
                if x_ch.get("stream_url") not in seen_streams:
                    all_channels.append(x_ch)
                    seen_streams.add(x_ch.get("stream_url"))

    # 5. Fetch saluran aktif dari API Player
    if APIPLAYER_CATALOG_DATA or APIPLAYER_CACHE_FILE:
        print("Memuat siaran aktif dari API Player...")
        ap_channels = fetch_apiplayer_channels(APIPLAYER_CATALOG_DATA, APIPLAYER_CACHE_FILE)
        if ap_channels:
            print(f"Berhasil memuat {len(ap_channels)} saluran aktif dari API Player!")
            seen_streams = {c.get("stream_url") for c in all_channels}
            added_ap = 0
            for ap_ch in ap_channels:
                s_url = ap_ch.get("stream_url", "").strip()
                if s_url and s_url not in seen_streams:
                    all_channels.append(ap_ch)
                    seen_streams.add(s_url)
                    added_ap += 1
            print(f"Total saluran unik baru dari API Player yang digabungkan: {added_ap}")

    if not all_channels:
        if output_path.exists() and output_path.stat().st_size > 1000:
            print(f"Katalog terenkripsi {output_path} sudah ada dan valid.")
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
