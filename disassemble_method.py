import struct

def disassemble(filepath, offset):
    with open(filepath, 'rb') as f:
        f.seek(offset)
        header = f.read(16)
        
    registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack('<HHHHII', header)
    print(f"Offset: {offset}")
    print(f"  registers_size: {registers_size}")
    print(f"  ins_size: {ins_size}")
    print(f"  outs_size: {outs_size}")
    print(f"  tries_size: {tries_size}")
    print(f"  debug_info_off: {debug_info_off}")
    print(f"  insns_size: {insns_size}")
    
    with open(filepath, 'rb') as f:
        f.seek(offset + 16)
        insns = f.read(insns_size * 2)
        
    # Print the hex dump of instructions
    print("Bytecode Hex:")
    hex_str = insns.hex()
    print(" ".join(hex_str[i:i+2] for i in range(0, len(hex_str), 2)))
    print("=" * 80)

if __name__ == '__main__':
    disassemble('bittv_extracted/classes2.dex', 2895248)
