#!/usr/bin/env python3
"""
sync_kltra_meta.py
Automated sync engine for Kltra TV dynamic configuration.
Extracts live domain, API origin, randomized catalog paths, and dynamic XOR key.
Encrypts metadata to assets/kltra_meta.enc for zero-code-change client updates.
"""

import os
import sys
import json
import base64
import subprocess
from pathlib import Path

def load_local_properties() -> dict:
    props = {}
    p = Path("local.properties")
    if p.exists():
        with open(p, "r", encoding="utf-8", errors="ignore") as f:
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

def xor_encrypt(text: str, key: str) -> str:
    data_bytes = text.encode("utf-8")
    key_bytes = key.encode("utf-8")
    key_len = len(key_bytes)
    encrypted = bytes([b ^ key_bytes[i % key_len] for i, b in enumerate(data_bytes)])
    return base64.b64encode(encrypted).decode("utf-8")

def main():
    print("=== Kltra TV Dynamic Metadata Sync ===")
    xor_key = get_secret("XR3EDTV_XOR_KEY")
    if not xor_key:
        print("ERROR: XR3EDTV_XOR_KEY tidak ditemukan di environment atau local.properties.")
        sys.exit(1)

    script_path = Path(__file__).resolve().parent / "extract_kltra.js"
    if not script_path.exists():
        print(f"ERROR: {script_path} tidak ditemukan.")
        sys.exit(1)

    print("Menjalankan extract_kltra.js...")
    try:
        proc = subprocess.run(
            ["node", str(script_path)],
            capture_output=True,
            text=True,
            timeout=25
        )
    except Exception as e:
        print(f"ERROR: Gagal mengeksekusi node: {e}")
        sys.exit(1)

    if proc.returncode != 0:
        print(f"ERROR: Ekstraksi gagal (code {proc.returncode}):\n{proc.stderr}")
        sys.exit(1)

    output = proc.stdout.strip()
    try:
        config = json.loads(output)
    except Exception as e:
        print(f"ERROR: Output bukan JSON valid: {e}\nRaw output: {output[:300]}")
        sys.exit(1)

    landing_url = config.get("landingUrl", "")
    api_origin = config.get("apiOrigin", "")
    extracted_key = config.get("xorKey", "")
    events_path = config.get("catalogPaths", {}).get("events", "")

    if not landing_url or not api_origin or not extracted_key or not events_path:
        print("ERROR: Konfigurasi yang diekstrak tidak lengkap:", config)
        sys.exit(1)

    print(f"Landing URL : {landing_url}")
    print(f"API Origin  : {api_origin}")
    print(f"Events Path : {events_path}")
    print(f"XOR Key Len : {len(extracted_key)} chars")

    json_str = json.dumps(config, ensure_ascii=False)
    enc_meta = xor_encrypt(json_str, xor_key)

    out_file = Path("assets/kltra_meta.enc")
    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text(enc_meta, encoding="utf-8")

    print(f"SUKSES! Metadata Kltra tersimpan di {out_file} ({len(enc_meta)} bytes).")

if __name__ == "__main__":
    main()
