import re
import xml.etree.ElementTree as ET
import json
import html

def rot47(text):
    result = []
    for c in text:
        y = ord(c)
        if 33 <= y <= 79:
            result.append(chr(y + 47))
        elif 80 <= y <= 126:
            result.append(chr(y - 47))
        else:
            result.append(c)
    return "".join(result)

def decrypt_url(url):
    if not url:
        return ""
    # Unescape HTML entities
    unescaped = html.unescape(url)
    if unescaped.startswith('@y@@yy1111@'):
        decrypted_raw = rot47(unescaped)
        # In Kotlin: val decryptedUrl = decryptedRaw.substring(8)
        # Let's check where the original code cuts it.
        # Wait, the prefix is "@y@@yy1111@" which is 11 characters.
        # If we rot47 "@y@@yy1111@", what does it become?
        # Let's print rot47 of the whole unescaped string, and see where the actual URL starts.
        return decrypted_raw
    return unescaped

def parse_xml():
    # Read XML file
    with open('netx_sh.xml', 'r', encoding='utf-8') as f:
        content = f.read()
    
    # We can use regex to find all <string> tags since XML might have issues if it has raw unescaped chars
    pattern = re.compile(r'<string name="s(\d+)_([^"]+)">(.*?)</string>')
    matches = pattern.findall(content)
    
    channels = {}
    for ch_id, key, val in matches:
        if ch_id not in channels:
            channels[ch_id] = {}
        channels[ch_id][key] = val
        
    # Also look for ints or other types
    int_pattern = re.compile(r'<int name="s(\d+)_([^"]+)" value="(\d+)" />')
    int_matches = int_pattern.findall(content)
    for ch_id, key, val in int_matches:
        if ch_id not in channels:
            channels[ch_id] = {}
        channels[ch_id][key] = int(val)
        
    # Filter only those that have a title
    active_channels = []
    for ch_id, info in channels.items():
        if 'tit' in info:
            raw_url = info.get('url', '')
            decrypted_url = decrypt_url(raw_url)
            
            # If the decrypted URL starts with rot47 of prefix, let's see.
            # Let's print one sample to console.
            active_channels.append({
                'id': ch_id,
                'title': info.get('tit'),
                'raw_url': raw_url,
                'decrypted_url': decrypted_url,
                'idgo': info.get('idgo', ''),
                'k': info.get('k', ''),
                'tipo': info.get('tipo', '')
            })
            
    print(f"Total active channels: {len(active_channels)}")
    
    # Print the first 10 with decrypted URL
    for ch in active_channels:
        if ch['raw_url'].startswith('@y@'):
            print(f"Title: {ch['title']}")
            print(f"Raw: {ch['raw_url']}")
            print(f"Decrypted: {ch['decrypted_url']}")
            print("-" * 50)
            break
            
    # Write all to a JSON file
    with open('parsed_channels.json', 'w', encoding='utf-8') as out:
        json.dump(active_channels, out, indent=2)

if __name__ == '__main__':
    parse_xml()
