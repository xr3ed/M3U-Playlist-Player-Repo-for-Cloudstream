import re

def find_classes(file_path):
    with open(file_path, 'rb') as f:
        data = f.read()
        
    pattern = re.compile(b'Lcom/live_streaming_tv/online_tv/[a-zA-Z0-9_$]+;')
    matches = pattern.findall(data)
    
    classes = sorted(list(set([m.decode('utf-8', errors='ignore') for m in matches])))
    print(f"File: {file_path}")
    print(f"Total classes found: {len(classes)}")
    for c in classes:
        print(f" - {c}")
    print("=" * 80)

if __name__ == '__main__':
    find_classes('bittv_extracted/classes.dex')
    find_classes('bittv_extracted/classes2.dex')
