"""Recompose APK while preserving every original entry except four approved ones."""
import argparse,copy,json,struct,zipfile
from pathlib import Path
from patch_apk import check_source,PACKAGE,NATIVE

def rename_table(raw):
    result=bytearray(raw)
    kind,header,size=struct.unpack_from('<HHI',raw)
    if kind!=2 or size!=len(raw):raise ValueError('Invalid resource table')
    off=header;changed=0
    while off<len(raw):
        kind,head,length=struct.unpack_from('<HHI',raw,off)
        if length<head or off+length>len(raw):raise ValueError('Invalid resource chunk')
        if kind==0x200 and struct.unpack_from('<I',raw,off+8)[0]==0x7f:
            old=raw[off+12:off+268].decode('utf-16le').split('\0')[0]
            if old!='org.mavlink.qgroundcontrol':raise ValueError('Wrong original resource package')
            name=PACKAGE.encode('utf-16le')
            if len(name)>254:raise ValueError('Package name too long')
            result[off+12:off+268]=name+bytes(256-len(name));changed+=1
        off+=length
    if changed!=1:raise ValueError('Expected one package table')
    return bytes(result)

def signature_entry(name):
    n=name.upper()
    return n.startswith('META-INF/') and (n=='META-INF/MANIFEST.MF' or n.endswith(('.SF','.RSA','.DSA','.EC')))

def recompose(source,rebuilt,decoded,out,report):
    check_source(Path(source).read_bytes());decoded=Path(decoded)
    with zipfile.ZipFile(source) as src,zipfile.ZipFile(rebuilt) as built:
        replacements={'AndroidManifest.xml':built.read('AndroidManifest.xml'),
                      'resources.arsc':rename_table(src.read('resources.arsc')),
                      NATIVE:(decoded/NATIVE).read_bytes(),
                      'assets/android_rcc_bundle.rcc':(decoded/'assets/android_rcc_bundle.rcc').read_bytes()}
        with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as dest:
            for info in src.infolist():
                if not signature_entry(info.filename):dest.writestr(copy.copy(info),replacements.get(info.filename,src.read(info.filename)))
        with zipfile.ZipFile(out) as dest:
            unchanged=[]
            for info in src.infolist():
                name=info.filename
                if signature_entry(name):continue
                actual=dest.read(name)
                if name in replacements:
                    if actual!=replacements[name]:raise ValueError('Replacement mismatch')
                else:
                    if actual!=src.read(name):raise ValueError('Unexpected change: '+name)
                    unchanged.append(name)
            if len(dest.namelist())!=len(unchanged)+len(replacements):raise ValueError('Unexpected APK entries')
    Path(report).write_text(json.dumps({'changed_entries':sorted(replacements),'unchanged_entries':unchanged},indent=2)+'\n')
    print('Verified original bytes for',len(unchanged),'entries; four approved replacements')

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['source','rebuilt','decoded','out','report']:p.add_argument(name)
    a=p.parse_args();recompose(**vars(a))
