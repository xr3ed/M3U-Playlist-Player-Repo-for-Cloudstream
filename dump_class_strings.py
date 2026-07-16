import struct
from dexparser import Dexparser

def inspect_code_strings(filepath):
    print(f"Inspecting {filepath}...")
    dp = Dexparser(filedir=filepath)
    classdefs = dp.get_classdef_data()
    
    typeids = dp.get_typeids()
    strings = dp.get_strings()
    
    with open(filepath, 'rb') as f:
        dex_bytes = f.read()
        
    for idx, c_def in enumerate(classdefs):
        class_idx = c_def['class_idx']
        type_str_idx = typeids[class_idx]
        class_name_bytes = strings[type_str_idx]
        class_name = class_name_bytes.decode('utf-8', errors='ignore')
        
        if 'live_streaming_tv' in class_name:
            print(f"\nClass: {class_name}")
            class_data_off = c_def['class_data_off']
            if class_data_off > 0:
                class_data = dp.get_class_data(class_data_off)
                for method_type in ['direct_methods', 'virtual_methods']:
                    m_list = class_data.get(method_type, [])
                    for m in m_list:
                        code_off = m.get('code_off', 0)
                        if code_off > 0:
                            # Parse code item
                            # registers_size (2), ins_size (2), outs_size (2), tries_size (2), debug_info_off (4), insns_size (4)
                            # total 16 bytes header before instructions
                            insns_size_bytes = dex_bytes[code_off + 12 : code_off + 16]
                            insns_size = struct.unpack('<I', insns_size_bytes)[0]
                            
                            insns_start = code_off + 16
                            insns_end = insns_start + insns_size * 2
                            insns = dex_bytes[insns_start : insns_end]
                            
                            # Scan instructions for const-string (opcode 1a) and const-string/jumbo (opcode 1b)
                            # instructions are 16-bit code units
                            referenced_strings = []
                            i = 0
                            while i < len(insns):
                                opcode = insns[i]
                                if opcode == 0x1a: # const-string
                                    # format: 1a AA BBBB
                                    # BB BB is at insns[i+2:i+4]
                                    if i + 3 < len(insns):
                                        str_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
                                        if str_idx < len(strings):
                                            referenced_strings.append(strings[str_idx].decode('utf-8', errors='ignore'))
                                    i += 4
                                elif opcode == 0x1b: # const-string/jumbo
                                    # format: 1b AA BBBB BBBB
                                    if i + 5 < len(insns):
                                        str_idx = struct.unpack('<I', insns[i+2 : i+6])[0]
                                        if str_idx < len(strings):
                                            referenced_strings.append(strings[str_idx].decode('utf-8', errors='ignore'))
                                    i += 6
                                else:
                                    # Advance by 2 bytes (1 code unit)
                                    i += 2
                                    
                            if referenced_strings:
                                # Get method name
                                # Let's print method details and referenced strings
                                print(f"  Method at code offset {code_off}:")
                                for s in set(referenced_strings):
                                    print(f"    - {repr(s)}")

if __name__ == '__main__':
    inspect_code_strings('bittv_extracted/classes.dex')
    inspect_code_strings('bittv_extracted/classes2.dex')
