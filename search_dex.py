import re

def search_dex(file_path):
    with open(file_path, 'rb') as f:
        data = f.read()
    
    # We want to find references to strings like 'eksm3', 'castarapp', 'paketapp'
    keywords = [b'eksm3', b'castarapp', b'paketapp', b'@y@@yy']
    for kw in keywords:
        idx = 0
        while True:
            idx = data.find(kw, idx)
            if idx == -1:
                break
            print(f"Found {kw} in {file_path} at offset {idx}")
            # Print surrounding bytes as string if printable
            start = max(0, idx - 100)
            end = min(len(data), idx + 200)
            chunk = data[start:end]
            printable = []
            for b in chunk:
                if 32 <= b <= 126:
                    printable.append(chr(b))
                else:
                    printable.append('.')
            print("Surrounding text:")
            print("".join(printable))
            print("=" * 80)
            idx += len(kw)

if __name__ == '__main__':
    search_dex('bittv_extracted/classes.dex')
    search_dex('bittv_extracted/classes2.dex')
