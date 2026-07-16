import struct
from dexparser import Dexparser

def find_u1_initialization(filepath):
    print(f"Searching for U1 write in {filepath}...")
    dp = Dexparser(filedir=filepath)
    classdefs = dp.get_classdef_data()
    
    typeids = dp.get_typeids()
    strings = dp.get_strings()
    methods = dp.get_methods()
    fields = dp.get_fieldids()
    
    with open(filepath, 'rb') as f:
        dex_bytes = f.read()
        
    for idx, c_def in enumerate(classdefs):
        class_idx = c_def['class_idx']
        type_str_idx = typeids[class_idx]
        class_name_bytes = strings[type_str_idx]
        class_name = class_name_bytes.decode('utf-8', errors='ignore')
        
        class_data_off = c_def['class_data_off']
        if class_data_off > 0:
            class_data = dp.get_class_data(class_data_off)
            for method_type in ['direct_methods', 'virtual_methods']:
                m_list = class_data.get(method_type, [])
                for m in m_list:
                    code_off = m.get('code_off', 0)
                    if code_off > 0:
                        # Parse code header
                        insns_size_bytes = dex_bytes[code_off + 12 : code_off + 16]
                        insns_size = struct.unpack('<I', insns_size_bytes)[0]
                        
                        insns_start = code_off + 16
                        insns_end = insns_start + insns_size * 2
                        insns = dex_bytes[insns_start : insns_end]
                        
                        # Scan instructions for iput-object (opcode 5b)
                        i = 0
                        while i < len(insns):
                            opcode = insns[i]
                            if opcode in [0x54, 0x5b, 0x62, 0x69]: # iget/iput/sget/sput
                                # field idx is 16-bit at offset + 2
                                if i + 3 < len(insns):
                                    field_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
                                    if field_idx < len(fields):
                                        field_info = fields[field_idx]
                                        f_name = strings[field_info['name_idx']].decode('utf-8', errors='ignore')
                                        if f_name in ['U1', 'T1', 'V1']:
                                            # Print instruction details
                                            # We want to know what value was loaded before this instruction
                                            # Let's print preceding instructions
                                            print(f"Match found in {class_name} method {m.get('method_idx')} at code offset {code_off}, instruction offset {i}:")
                                            print(f"  Opcode: {hex(opcode)}, Field: {f_name}")
                                            
                                            # Let's dump surrounding instructions
                                            start_idx = max(0, i - 16)
                                            end_idx = min(len(insns), i + 8)
                                            print(f"  Surrounding bytecode: {insns[start_idx:end_idx].hex()}")
                            
                            # Advance by instruction length (approximate or just 2 bytes at a time)
                            i += 2

if __name__ == '__main__':
    find_u1_initialization('bittv_extracted/classes2.dex')
