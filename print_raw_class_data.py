from dexparser import Dexparser

def print_raw():
    dp = Dexparser(filedir='bittv_extracted/classes2.dex')
    classdefs = dp.get_classdef_data()
    typeids = dp.get_typeids()
    strings = dp.get_strings()
    
    for c_def in classdefs:
        class_name = strings[typeids[c_def['class_idx']]]
        if b'BitTVActivity' in class_name:
            print("Found BitTVActivity")
            class_data = dp.get_class_data(c_def['class_data_off'])
            print("Direct methods raw:")
            for m in class_data.get('direct_methods', []):
                print(m)
            print("Virtual methods raw:")
            for m in class_data.get('virtual_methods', []):
                print(m)

if __name__ == '__main__':
    print_raw()
