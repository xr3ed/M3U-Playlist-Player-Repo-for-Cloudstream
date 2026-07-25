import json
import hashlib
import os

def main():
    manifest_path = "build-out/plugins.json"
    if not os.path.exists(manifest_path):
        print("Manifest not found, skipping hash recalculation.")
        return

    with open(manifest_path, "r", encoding="utf-8") as f:
        plugins = json.load(f)

    updated = 0
    for p in plugins:
        name = p.get("internalName", "")
        cs3_path = f"build-out/{name}.cs3"
        if os.path.exists(cs3_path):
            with open(cs3_path, "rb") as f:
                data = f.read()
            new_hash = "sha256-" + hashlib.sha256(data).hexdigest()
            new_size = len(data)
            if p.get("fileHash") != new_hash or p.get("fileSize") != new_size:
                print(f"Updated hash for {name}: {p.get('fileHash','?')} -> {new_hash}")
                p["fileHash"] = new_hash
                p["fileSize"] = new_size
                updated += 1

    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(plugins, f, indent=2, ensure_ascii=False)
    print(f"Recalculated hashes for {updated} plugin(s)")

if __name__ == "__main__":
    main()
