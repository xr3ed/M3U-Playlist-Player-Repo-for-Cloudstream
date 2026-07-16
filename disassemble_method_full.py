import struct
import sys
from dexparser import Dexparser

def disassemble(filepath, offset):
    print(f"Disassembling {filepath} at offset {offset}...")
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
    while i < len(insns):
        opcode = insns[i]
        
        # Determine length and format of the instruction based on opcode
        if opcode == 0x1a: # const-string
            reg = insns[i+1]
            str_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            val = strings[str_idx].decode('utf-8', errors='ignore') if str_idx < len(strings) else f"<idx:{str_idx}>"
            print(f"[{i:04x}] const-string v{reg}, {repr(val)}")
            i += 4
        elif opcode == 0x1c: # const-class
            reg = insns[i+1]
            type_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            type_str_idx = typeids[type_idx]
            val = strings[type_str_idx].decode('utf-8', errors='ignore') if type_str_idx < len(strings) else f"<idx:{type_idx}>"
            print(f"[{i:04x}] const-class v{reg}, {val}")
            i += 4
        elif opcode == 0x22: # new-instance
            reg = insns[i+1]
            type_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            type_str_idx = typeids[type_idx]
            val = strings[type_str_idx].decode('utf-8', errors='ignore') if type_str_idx < len(strings) else f"<idx:{type_idx}>"
            print(f"[{i:04x}] new-instance v{reg}, {val}")
            i += 4
        elif opcode in [0x54, 0x5b]: # iget-object, iput-object
            op_name = "iget-object" if opcode == 0x54 else "iput-object"
            reg = insns[i+1] # contains A and B (reg A, obj B)
            field_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            
            # Resolve field name
            if field_idx < len(fields):
                field_info = fields[field_idx]
                class_type = strings[typeids[field_info['class_idx']]].decode('utf-8', errors='ignore')
                field_name = strings[field_info['name_idx']].decode('utf-8', errors='ignore')
                field_str = f"{class_type}->{field_name}"
            else:
                field_str = f"<field:{field_idx}>"
            
            print(f"[{i:04x}] {op_name} v{reg & 0xf}, v{reg >> 4}, {field_str}")
            i += 4
        elif opcode in [0x6e, 0x70, 0x71, 0x72]: # invoke-virtual, invoke-direct, invoke-static, invoke-interface
            op_names = {0x6e: "invoke-virtual", 0x70: "invoke-direct", 0x71: "invoke-static", 0x72: "invoke-interface"}
            op_name = op_names[opcode]
            
            args = insns[i+1]
            meth_idx = struct.unpack('<H', insns[i+2 : i+4])[0]
            
            # Resolve method name
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
        elif opcode == 0x12: # const/4
            reg_val = insns[i+1]
            reg = reg_val & 0xf
            val = reg_val >> 4
            print(f"[{i:04x}] const/4 v{reg}, {val}")
            i += 2
        elif opcode == 0x28: # goto
            offset_val = struct.unpack('<b', insns[i+1 : i+2])[0]
            print(f"[{i:04x}] goto {offset_val:+d} (target: {i + offset_val * 2:04x})")
            i += 2
        elif opcode == 0x38: # if-eq
            reg_val = insns[i+1]
            offset_val = struct.unpack('<h', insns[i+2 : i+4])[0]
            print(f"[{i:04x}] if-eq v{reg_val & 0xf}, v{reg_val >> 4}, {offset_val:+d} (target: {i + offset_val * 2:04x})")
            i += 4
        else:
            # Print unknown instruction hex bytes
            hex_data = insns[i:i+2].hex()
            print(f"[{i:04x}] db {hex_data}")
            i += 2

if __name__ == '__main__':
    filepath = sys.argv[1] if len(sys.argv) > 1 else 'bittv_extracted/classes2.dex'
    offset = int(sys.argv[2]) if len(sys.argv) > 2 else 2895248
    disassemble(filepath, offset)
