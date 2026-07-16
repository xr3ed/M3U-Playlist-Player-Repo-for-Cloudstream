import struct
from dexparser import Dexparser

def resolve():
    dp = Dexparser(filedir='bittv_extracted/classes2.dex')
    strings = dp.get_strings()
    
    # We saw in dump of find_u1_init.py:
    # Match in Ljb/f; method None at code offset 4083876
    # Instruction offset 1168: Field T1
    # Instruction offset 1192: Field U1
    
    # Let's inspect the code of Ljb/f; at offset 4083876
    with open('bittv_extracted/classes2.dex', 'rb') as f:
        f.seek(4083876)
        header = f.read(16)
    registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack('<HHHHII', header)
    
    with open('bittv_extracted/classes2.dex', 'rb') as f:
        f.seek(4083876 + 16)
        insns = f.read(insns_size * 2)
        
    # Let's search all const-string instructions and the fields they write to nearby
    i = 0
    while i < len(insns):
        opcode = insns[i]
        if opcode == 0x1a:
            reg = insns[i+1]
            str_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            val = strings[str_idx].decode('utf-8', errors='ignore') if str_idx < len(strings) else f"<idx:{str_idx}>"
            print(f"[{i:04x}] const-string v{reg}, {repr(val)}")
            i += 4
        elif opcode == 0x5b: # iput-object
            reg = insns[i+1]
            field_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            field_info = dp.get_fieldids()[field_idx]
            f_name = strings[field_info['name_idx']].decode('utf-8', errors='ignore')
            print(f"[{i:04x}] iput-object v{reg & 0xf}, {f_name}")
            i += 4
        elif opcode == 0x6e: # invoke-virtual
            i += 6
        else:
            i += 2

if __name__ == '__main__':
    resolve()
