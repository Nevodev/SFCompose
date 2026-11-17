"""BVG numeric and path coding, checked against the macOS CoreUI decoder."""
class Reader:
    def __init__(self,data):self.data=data;self.nibble=0
    def available(self):return self.nibble//2<len(self.data)
    def uint(self,count):
        value=0
        for _ in range(count):
            byte=self.data[self.nibble//2];value=(value<<4)|((byte>>(4 if self.nibble%2==0 else 0))&15);self.nibble+=1
        return value
    def byte(self):return self.uint(2)
    def scalar(self,kind):
        flag=self.uint(1)
        if kind:
            bits=[4,8,12,32][(flag>>1)&3];whole=((flag&1)<<bits)|self.uint(bits//4)
        else:whole=flag&7
        value=float(whole)
        if not flag&8:return value
        decimals=self.uint(1)
        if not decimals&4:
            extra=self.uint(1);form=decimals&3;leading=0
            if form==0:fraction=extra
            elif form==1:
                bits=[10,14,26,38][(extra>>2)&3]-2;fraction=self.uint(bits//4)|((extra&3)<<bits)
            elif form==2:
                bits=[12,16,28,40][extra&3];leading=(extra>>2)&3;fraction=self.uint(bits//4)
            else:
                leading=extra;descriptor=self.uint(1);bits=[10,14,26,38][(descriptor>>2)&3]-2;fraction=self.uint(bits//4)|((descriptor&3)<<bits)
            if fraction:value+=fraction/(10.0**(len(str(fraction))+leading))
            if not decimals&8:return value
        exponent=self.uint(1)
        if not exponent&4:raise ValueError('Invalid BVG exponent')
        power=self.uint([8,12][exponent&1]//4)
        return value/(10.0**power)if exponent&2 else value*(10.0**power)
    def pair(self,packed,kind):
        if not packed:return self.scalar(kind),self.scalar(kind)
        flag=(self.data[self.nibble//2]>>(4 if self.nibble%2==0 else 0))&15
        widths=[([8,10,12,14]if flag&bit else[4,6,8,10])[kind]for bit in [8,4]]
        value=self.uint(sum(widths)//4)
        return float((value>>(widths[1]-1))&((1<<(widths[0]-1))-1)),float(value&((1<<(widths[1]-1))-1))
    def run(self,flag,kind):
        if flag&4:kind=self.uint(1)
        count=(flag>>4)+1 if flag&8 else self.uint(flag>>4)
        return kind,count,(-1.0 if flag&2 else 1.0),(-1.0 if flag&1 else 1.0)

def decode_numbers(data):
    reader=Reader(data)
    if reader.byte()!=0:raise ValueError('Unsupported BVG numeric list')
    header=reader.byte();count=(header&31)if header&64 else reader.uint(header&3)
    if count==0:raise ValueError('Empty BVG numeric list')
    kind=0;values=[]
    for run_index in range(count):
        kind,pairs,sx,sy=reader.run(reader.byte(),kind)
        for pair_index in range(pairs):
            x,y=reader.pair(bool(header&128),kind);values.append(x*sx)
            if not(header&32 and run_index==count-1 and pair_index==pairs-1):values.append(y*sy)
    return values

def decode_path(data):
    reader=Reader(data)
    if reader.byte()!=0:raise ValueError('Unsupported BVG path')
    reader.uint(4);reader.uint(4)
    commands=[];in_subpath=False
    while len(data)*2-reader.nibble>=2:
        header=reader.byte()
        if header>=240:raise ValueError('Invalid BVG command')
        if header&240==112:commands.append(['Z']);in_subpath=False;continue
        if header&3:runs=reader.uint(header&3);flag=reader.byte();kind=0
        else:
            runs=1;nibble=reader.uint(1);kind=nibble&3;flag=((nibble>>2)&3)|8
        opcode=header>>4
        verb={1:'C',2:'S',3:'Q',4:'T',5:'H',6:'V',9:'c',10:'s',11:'q',12:'t',13:'h',14:'v'}.get(opcode)
        if verb is None:verb=('l'if in_subpath else'm')if opcode==8 else('L'if in_subpath else'M')
        command=[verb];in_subpath=True
        for i in range(runs):
            kind,pairs,sx,sy=reader.run(flag,kind)
            for _ in range(pairs):
                x,y=reader.pair(bool(header&8),kind);command.extend([x*sx,y*sy])
            if i<runs-1:flag=reader.byte()
        if header&4:command.pop()
        commands.append(command)
    return commands

def decode(data,path=False):return decode_path(data)if path else decode_numbers(data)
