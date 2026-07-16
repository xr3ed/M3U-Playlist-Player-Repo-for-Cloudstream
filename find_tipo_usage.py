import struct
from dexparser import Dexparser

def find_usage(filepath):
    dp = Dexparser(filedir=filepath)
    strings = dp.get_strings()
    typeids = dp.get_typeids()
    methods = dp.get_methods()
    fields = dp.get_fieldids()
    
    with open(filepath, 'rb') as f:
        dex_bytes = f.read()
        
    classdefs = dp.get_classdef_data()
    for idx, c_def in enumerate(classdefs):
        class_idx = c_def['class_idx']
        class_name = strings[typeids[class_idx]].decode('utf-8', errors='ignore')
        
        class_data_off = c_def['class_data_off']
        if class_data_off > 0:
            class_data = dp.get_class_data(class_data_off)
            for method_type in ['direct_methods', 'virtual_methods']:
                m_list = class_data.get(method_type, [])
                for m in m_list:
                    code_off = m.get('code_off', 0)
                    if code_off > 0:
                        # Parse code header
                        insns_size = struct.unpack('<I', dex_bytes[code_off + 12 : code_off + 16])[0]
                        insns = dex_bytes[code_off + 16 : code_off + 16 + insns_size * 2]
                        
                        # Scan instructions
                        i = 0
                        while i < len(insns):
                            opcode = insns[i]
                            if opcode in [0x52, 0x53, 0x54, 0x55, 0x59, 0x5a, 0x5b, 0x5c]: # iget/iput family
                                if i + 3 < len(insns):
                                    field_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
                                    if field_idx < len(fields):
                                        f_info = fields[field_idx]
                                        f_name = strings[f_info['name_idx']].decode('utf-8', errors='ignore')
                                        if 'tipo' in f_name.lower():
                                            print(f"Field access '{f_name}' in class {class_name} method at offset {code_off}")
                            elif opcode in [0x6e, 0x70, 0x71, 0x72]: # invoke
                                if i + 3 < len(insns):
                                    meth_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
                                    if meth_idx < len(methods):
                                        m_info = methods[meth_idx]
                                        m_name = strings[m_info['name_idx']].decode('utf-8', errors='ignore')
                                        if 'tipo' in m_name.lower():
                                            print(f"Method invoke '{m_name}' in class {class_name} method at offset {code_off}")
                            i += 2

if __name__ == '__main__':
    find_usage('bittv_extracted/classes2.dex')
