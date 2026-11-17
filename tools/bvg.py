from pathlib import Path
import json
import struct
import sys
import hashlib
from bvg_numbers import decode

BUILTIN = ['svg', 'g', 'viewBox', 'path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'width', 'height', 'x', 'y', 'cx', 'cy', 'r', 'rx', 'ry', 'fill', 'opacity', 'transform', 'd', 'points', 'id', 'class', 'x1', 'y1', 'x2', 'y2', 'none', '0', '1', '0.0', '-sfsymbols-motion-group', '-sfsymbols-variable-threshold', '-sfsymbols-layer-tags', '-sfsymbols-always-rotates', '-sfsymbols-draw-reverses-motion-groups', '-sfsymbols-variable-value-mode', '-sfsymbols-vc-continuous', '-sfsymbols-always-pulses', '-sfsymbols-clear-behind', '-sfsymbols-fill-action', '-sfsymbols-indices', '-sfsymbols-variable-draw', '-sfsymbols-clipstroke-attachments', '-sfsymbols-wiggle-style', '-sfsymbols-rotates-clockwise', 'color', 'draw', 'erase', '.defaults', 'true', 'false', 'primary', 'secondary', 'tertiary', 'multicolor', 'hierarchical', 'monochrome', 'data-clipstroke-keyframes', 'LayerTree', 's', 'ra', 'da', 'black', 'controlAccentColor', 'knockout', 'labelColor', 'secondaryLabelColor', 'tertiaryLabelColor', 'quaternaryLabelColor', 'systemBlueColor', 'systemBrownColor', 'systemCyanColor', 'systemGrayColor', 'systemGreenColor', 'systemIndigoColor', 'systemMintColor', 'systemOrangeColor', 'systemPinkColor', 'systemPurpleColor', 'systemRedColor', 'systemTealColor', 'systemYellowColor', 'tintColor', 'white', 'custom']
def read_bvg(path):
    data=path.read_bytes()
    assert data[:5]==b'BVG \x01' and data[5] in [0x11,0x12],data[:6].hex()
    offset_width=data[5]&15
    offset_format={1:'B',2:'H'}[offset_width]
    def offset_at(cursor):return struct.unpack_from('<'+offset_format,data,cursor)[0]
    nodes_offset,styles_offset=offset_at(6),offset_at(6+offset_width)
    count_cursor=6+2*offset_width
    count=data[count_cursor];offsets=struct.unpack_from('<'+str(count+1)+offset_format,data,count_cursor+1)
    strings=[data[a:b]for a,b in zip(offsets,offsets[1:])]
    def raw(index):return strings[index-len(BUILTIN)]
    def string(index):return BUILTIN[index]if index<len(BUILTIN)else raw(index).decode('utf-8')
    def value(key,index):
        if key in [2,61]:return decode(raw(index))
        if key==22:return decode(raw(index),path=True)
        return string(index)
    styles=[];style_count=data[styles_offset]
    for i in range(style_count):
        entry=styles_offset+1+(3+offset_width)*i
        selectorType,identifier,qualifier=struct.unpack_from('<BBB',data,entry)
        offset=offset_at(entry+3)
        cursor=styles_offset+offset;decls=[]
        for _ in range(data[cursor]):
            key,length=struct.unpack_from('<BB',data,cursor+1)
            decls.append({'key':string(key),'value':' '.join(string(index) for index in data[cursor+3:cursor+3+length])})
            cursor+=length+2
        prefixes=['.monochrome-','.multicolor-','.hierarchical-','.shapeGroupLayer-']
        selector=prefixes[selectorType&0x7f]+str(identifier)if selectorType&0x80 else string(qualifier)
        if selectorType&0x80 and qualifier!=255:selector+=':'+string(qualifier)
        styles.append({'selectorType':selectorType,'identifier':identifier,'qualifier':qualifier,'selector':selector,'declarations':decls})
    idcount=data[nodes_offset];ids={offset_at(nodes_offset+2+(1+offset_width)*i):string(data[nodes_offset+1+(1+offset_width)*i])for i in range(idcount)}
    root_offset=nodes_offset+1+(1+offset_width)*idcount
    def node(offset):
        tag,text,attr_count,class_count,child_count=struct.unpack_from('<5B',data,offset)
        cursor=offset+5;attributes={}
        for i in range(attr_count):
            key,index=struct.unpack_from('<BB',data,cursor+2*i);attributes[string(key)]=value(key,index)
        cursor+=2*attr_count
        classes=list(data[cursor:cursor+class_count]);cursor+=class_count
        children=struct.unpack_from('<'+str(child_count)+offset_format,data,cursor)if child_count else []
        return {'offset':offset-nodes_offset,'tag':string(tag),'id':ids.get(offset-nodes_offset),'attributes':attributes,'classIndices':classes,'children':[node(nodes_offset+child)for child in children]}
    return {'source':str(path),'sha256':hashlib.sha256(data).hexdigest(),'storageTag':data[5],'styles':styles,'tree':node(root_offset)}
