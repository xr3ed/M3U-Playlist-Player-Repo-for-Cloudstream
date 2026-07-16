from dexparser import Dexparser

def inspect_file(filepath):
    print(f"Inspecting {filepath}...")
    dp = Dexparser(filedir=filepath)
    classdefs = dp.get_classdef_data()
    
    typeids = dp.get_typeids()
    strings = dp.get_strings()
    methods = dp.get_methods()
    
    for idx, c_def in enumerate(classdefs):
        class_idx = c_def['class_idx']
        type_str_idx = typeids[class_idx]
        class_name_bytes = strings[type_str_idx]
        
        if b'live_streaming_tv' in class_name_bytes:
            class_name = class_name_bytes.decode('utf-8', errors='ignore')
            print(f"Class: {class_name}")
            class_data_off = c_def['class_data_off']
            if class_data_off > 0:
                class_data = dp.get_class_data(class_data_off)
                for method_type in ['direct_methods', 'virtual_methods']:
                    m_list = class_data.get(method_type, [])
                    print(f"  {method_type}:")
                    for m in m_list:
                        method_idx = m['diff'] # The absolute method index is in 'diff'
                        
                        if method_idx < len(methods):
                            method_id_info = methods[method_idx]
                            name_idx = method_id_info['name_idx']
                            method_name = strings[name_idx].decode('utf-8', errors='ignore')
                            print(f"    - {method_name} (Code offset: {m.get('code_off')})")
                        else:
                            print(f"    - [ERROR] method_idx {method_idx} out of range")
            print("-" * 50)

if __name__ == '__main__':
    inspect_file('bittv_extracted/classes2.dex')
