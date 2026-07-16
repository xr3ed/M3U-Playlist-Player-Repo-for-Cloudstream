import re

def search_keyword(file_path, keyword):
    with open(file_path, 'rb') as f:
        data = f.read()
    
    idx = 0
    while True:
        idx = data.find(keyword, idx)
        if idx == -1:
            break
        print(f"Found {keyword} in {file_path} at offset {idx}")
        start = max(0, idx - 150)
        end = min(len(data), idx + 250)
        chunk = data[start:end]
        printable = []
        for b in chunk:
            if 32 <= b <= 126:
                printable.append(chr(b))
            else:
                printable.append('.')
        print("".join(printable))
        print("=" * 80)
        idx += len(keyword)

if __name__ == '__main__':
    search_keyword('bittv_extracted/classes2.dex', b'duktek_')
    search_keyword('bittv_extracted/classes2.dex', b'bittvnew')
