import struct
from dexparser import Dexparser

def disassemble_interesting(filepath, offset):
    dp = Dexparser(filedir=filepath)
    strings = dp.get_strings()
    typeids = dp.get_typeids()
    methods = dp.get_methods()
    fields = dp.get_fieldids()
    
    with open(filepath, 'rb') as f:
        f.seek(offset)
        header = f.read(16)
        
    registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack('<HHHHII', header)
    
    with open(filepath, 'rb') as f:
        f.seek(offset + 16)
        insns = f.read(insns_size * 2)
        
    i = 0
    # ONLY print the first 0x800 bytes of instructions to avoid truncation!
    while i < min(len(insns), 0x800):
        opcode = insns[i]
        
        if opcode == 0x1a: # const-string
            reg = insns[i+1]
            str_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            val = strings[str_idx].decode('utf-8', errors='ignore') if str_idx < len(strings) else f"<idx:{str_idx}>"
            print(f"[{i:04x}] const-string v{reg}, {repr(val)}")
            i += 4
        elif opcode == 0x1c: # const-class
            reg = insns[i+1]
            type_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            if type_idx < len(typeids):
                type_str_idx = typeids[type_idx]
                val = strings[type_str_idx].decode('utf-8', errors='ignore') if type_str_idx < len(strings) else f"<idx:{type_idx}>"
            else:
                val = f"<type_idx:{type_idx} out of range>"
            print(f"[{i:04x}] const-class v{reg}, {val}")
            i += 4
        elif opcode == 0x22: # new-instance
            reg = insns[i+1]
            type_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            if type_idx < len(typeids):
                type_str_idx = typeids[type_idx]
                val = strings[type_str_idx].decode('utf-8', errors='ignore') if type_str_idx < len(strings) else f"<idx:{type_idx}>"
            else:
                val = f"<type_idx:{type_idx} out of range>"
            print(f"[{i:04x}] new-instance v{reg}, {val}")
            i += 4
        elif opcode in [0x54, 0x5b]: # iget-object, iput-object
            op_name = "iget-object" if opcode == 0x54 else "iput-object"
            reg = insns[i+1]
            field_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            if field_idx < len(fields):
                field_info = fields[field_idx]
                class_type = strings[typeids[field_info['class_idx']]].decode('utf-8', errors='ignore')
                field_name = strings[field_info['name_idx']].decode('utf-8', errors='ignore')
                field_str = f"{class_type}->{field_name}"
            else:
                field_str = f"<field:{field_idx}>"
            print(f"[{i:04x}] {op_name} v{reg & 0xf}, v{reg >> 4}, {field_str}")
            i += 4
        elif opcode in [0x6e, 0x70, 0x71, 0x72]: # invoke
            op_names = {0x6e: "invoke-virtual", 0x70: "invoke-direct", 0x71: "invoke-static", 0x72: "invoke-interface"}
            op_name = op_names[opcode]
            args = insns[i+1]
            meth_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            if meth_idx < len(methods):
                meth_info = methods[meth_idx]
                class_type = strings[typeids[meth_info['class_idx']]].decode('utf-8', errors='ignore')
                meth_name = strings[meth_info['name_idx']].decode('utf-8', errors='ignore')
                meth_str = f"{class_type}->{meth_name}"
            else:
                meth_str = f"<method:{meth_idx}>"
            print(f"[{i:04x}] {op_name} {{args:{args}}}, {meth_str}")
            i += 6
        elif opcode == 0x0c: # move-result-object
            reg = insns[i+1]
            print(f"[{i:04x}] move-result-object v{reg}")
            i += 2
        else:
            i += 2

if __name__ == '__main__':
    disassemble_interesting('bittv_extracted/classes2.dex', 2912584)
