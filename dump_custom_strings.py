import re

def find_custom_strings(file_path):
    with open(file_path, 'rb') as f:
        data = f.read()
        
    pattern = re.compile(b'[a-zA-Z0-9_/\\-\\.!@#\\$%\\^&\\*\\(\\)\\{\\}\\[\\]:;<>\\?,=\\+\\|~]{4,}')
    matches = pattern.findall(data)
    
    strings = sorted(list(set([m.decode('utf-8', errors='ignore') for m in matches])))
    
    # Let's filter by:
    # 1. Any package path containing live_streaming_tv or online_tv
    # 2. Any url (http/https)
    # 3. Any string containing duktek or bittv
    # 4. Any class names / method names that look like ours
    filtered = []
    for s in strings:
        s_lower = s.lower()
        if 'live_streaming_tv' in s_lower or 'online_tv' in s_lower or 'duktek' in s_lower or 'bittv' in s_lower:
            filtered.append(s)
        elif s_lower.startswith('http://') or s_lower.startswith('https://'):
            filtered.append(s)
            
    print(f"File: {file_path}")
    print(f"Matching strings count: {len(filtered)}")
    for s in filtered:
        print(f" - {s}")
    print("=" * 80)

if __name__ == '__main__':
    find_custom_strings('bittv_extracted/classes.dex')
    find_custom_strings('bittv_extracted/classes2.dex')
