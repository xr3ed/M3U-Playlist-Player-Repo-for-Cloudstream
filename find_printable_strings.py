import re

def find_strings_in_file(file_path):
    with open(file_path, 'rb') as f:
        data = f.read()
        
    # Find all sequences of printable ASCII chars of length >= 4
    pattern = re.compile(b'[a-zA-Z0-9_/\\-\\.!@#\\$%\\^&\\*\\(\\)\\{\\}\\[\\]:;<>\\?,=\\+\\|~]{4,}')
    matches = pattern.findall(data)
    
    # Filter and unique
    strings = sorted(list(set([m.decode('utf-8', errors='ignore') for m in matches])))
    
    # Search for keywords
    keywords = ['decrypt', 'encrypt', 'rot47', 'rot13', 'cipher', 'aes', 'secret', 'key', 'security', 'obfuscate']
    results = []
    for s in strings:
        for kw in keywords:
            if kw in s.lower():
                results.append(s)
                break
                
    print(f"File: {file_path}")
    print(f"Total matching strings: {len(results)}")
    for r in results[:100]:
        print(f" - {r}")
    print("=" * 80)

if __name__ == '__main__':
    find_strings_in_file('bittv_extracted/classes.dex')
    find_strings_in_file('bittv_extracted/classes2.dex')
