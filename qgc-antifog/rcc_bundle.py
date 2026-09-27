"""Qt RCC reader/writer; preserves file bytes and locale variants."""
import struct,zlib

def name_hash(name):
    h=0;b=name.encode('utf-16be')
    for i in range(0,len(b),2):
        h=(h<<4)+int.from_bytes(b[i:i+2],'big');h^=(h&0xf0000000)>>23;h&=0x0fffffff
    return h

def entries(blob,tree,names,data,version):
    size=14 if version==1 else 22;out=[];seen=set()
    def unpack(fmt,p):
        if p<0 or p+struct.calcsize(fmt)>len(blob):raise ValueError('Truncated RCC')
        return struct.unpack_from(fmt,blob,p)
    def walk(index,path):
        if index in seen:raise ValueError('Cyclic RCC tree')
        seen.add(index);p=tree+index*size;no,flags=unpack('>IH',p)
        n=unpack('>H',names+no)[0];start=names+no+6
        if start+n*2>len(blob):raise ValueError('Truncated RCC name')
        name=blob[start:start+n*2].decode('utf-16be') if index else ''
        if '/' in name or name in ('.','..'):raise ValueError('Invalid resource name')
        path=path+'/'+name if name else path
        if flags&2:
            count,first=unpack('>II',p+6)
            if count>len(blob)//size:raise ValueError('Invalid node count')
            for child in range(first,first+count):walk(child,path)
        else:
            country,language,offset=unpack('>HHI',p+6);pos=data+offset;n=unpack('>I',pos)[0]
            if pos+4+n>len(blob):raise ValueError('Truncated resource data')
            payload=blob[pos+4:pos+4+n]
            if flags&4:raise ValueError('Zstandard resource unsupported')
            if flags&1:
                if len(payload)<4:raise ValueError('Truncated compressed resource')
                expected=struct.unpack_from('>I',payload)[0];raw=zlib.decompress(payload[4:])
                if len(raw)!=expected:raise ValueError('Invalid compressed size')
            else:raw=payload
            out.append(dict(path=path,country=country,language=language,flags=flags,raw=raw,node=p,payload=pos,capacity=n))
    walk(0,'');return out

def read(blob):
    if len(blob)<20 or blob[:4]!=b'qres':raise ValueError('Not an RCC bundle')
    version,tree,data,names=struct.unpack_from('>IIII',blob,4)
    if version not in (1,2,3):raise ValueError('Unsupported RCC version')
    return [(e['path'],e['country'],e['language'],e['raw']) for e in entries(blob,tree,names,data,version)]

def build(records):
    root={'name':'','children':{}}
    identities=set()
    for path,country,language,raw in records:
        identity=(path,country,language)
        if identity in identities:raise ValueError('Duplicate resource')
        identities.add(identity);parts=path.strip('/').split('/');parent=root
        if any(p in ('','.','..') for p in parts):raise ValueError('Invalid resource path')
        for part in parts[:-1]:
            key=(part,None,None)
            parent=parent['children'].setdefault(key,{'name':part,'children':{}})
        parent['children'][(parts[-1],country,language)]={'name':parts[-1],'country':country,'language':language,'raw':raw}
    nodes=[root]
    for node in nodes:
        if 'children' in node:
            children=sorted(node['children'].values(),key=lambda n:(name_hash(n['name']),n['name'],n.get('country',-1),n.get('language',-1)))
            node['first']=len(nodes);node['count']=len(children);nodes.extend(children)
    namepool=bytearray();name_offsets={};payload=bytearray();table=bytearray()
    for node in nodes:
        name=node['name']
        if name not in name_offsets:
            encoded=name.encode('utf-16be');name_offsets[name]=len(namepool)
            namepool.extend(struct.pack('>HI',len(encoded)//2,name_hash(name))+encoded)
        no=name_offsets[name]
        if 'children' in node:table.extend(struct.pack('>IHII',no,2,node['count'],node['first']))
        else:
            raw=node['raw'];packed=struct.pack('>I',len(raw))+zlib.compress(raw,9);flags=1
            if len(packed)>=len(raw):packed=raw;flags=0
            off=len(payload);payload.extend(struct.pack('>I',len(packed))+packed)
            table.extend(struct.pack('>IHHHI',no,flags,node['country'],node['language'],off))
    return b'qres'+struct.pack('>IIII',1,20,20+len(table)+len(namepool),20+len(table))+table+namepool+payload
