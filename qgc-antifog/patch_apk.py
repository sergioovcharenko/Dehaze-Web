"""Apply bounded resource-only changes to the exact user-supplied QGC APK."""
import argparse,hashlib,io,json,re,struct,zipfile,zlib
from pathlib import Path
import xml.etree.ElementTree as ET
import rcc_bundle
from prepare_assets import generate_worker

SOURCE_SHA='bf45356100562e266ee99fd6b3bc1212901db83c52868a3ecd29ed9b0a0d42f2'
PACKAGE='org.mavlink.qgroundcontrol.antifog'
NATIVE='lib/arm64-v8a/libQGroundControl_arm64-v8a.so'
TABLE=(0x1a9d7c0,0x1a9f734,0x1aa3aa2,3)
TARGETS={
 '/qml/MainRootWindow.qml':'_ZN21QmlCacheGeneratedCode23_qml_MainRootWindow_qml7qmlDataE',
 '/toolbar/MainToolBarIndicators.qml':'_ZN21QmlCacheGeneratedCode34_toolbar_MainToolBarIndicators_qml7qmlDataE',
 '/qml/QGroundControl/FlightDisplay/FlightDisplayViewVideo.qml':'_ZN21QmlCacheGeneratedCode60_qml_QGroundControl_FlightDisplay_FlightDisplayViewVideo_qml7qmlDataE',
}

def check_source(blob):
    if hashlib.sha256(blob).hexdigest()!=SOURCE_SHA:raise ValueError('This is not the approved uploaded QGC APK')

def replace_payload(blob,e,raw):
    packed=struct.pack('>I',len(raw))+zlib.compress(raw,9) if e['flags']&1 else raw
    if len(packed)>e['capacity']:raise ValueError('New resource exceeds its original allocation')
    start=e['payload'];stop=start+4+e['capacity']
    blob[start:stop]=struct.pack('>I',len(packed))+packed+bytes(e['capacity']-len(packed))
    return (start,stop),len(packed)

def compact(source):
    source=re.sub(r'^\s*/\*.*?\*/','',source,count=1,flags=re.S)
    return '\n'.join(line.strip() for line in source.splitlines() if line.strip() and not line.lstrip().startswith('//'))+'\n'

def edited_qml(path,raw):
    s=raw.decode('utf-8')
    if path.endswith('/MainRootWindow.qml'):
        marker=re.search(r'^\s*id:\s*mainWindow\s*$',s,re.M)
        if not marker:raise ValueError('Main window anchor missing')
        props='''
property bool metiFogEnabled:false
property int metiFogMs:-1
property real metiFogStrength:.6
property string metiFogMode:"OFF"
property string metiFogError:""
'''
        s=s[:marker.end()]+props+s[marker.end():]
    elif path.endswith('/MainToolBarIndicators.qml'):
        s='import QtQuick 2.12\nimport "qrc:/meti_antifog" as MetiFog\nMetiFog.ToolbarAddon {}\n'
    else:
        s,n=re.subn(r'^(import QtQuick[ \t]+2\.\d+[ \t]*)$',r'\1\nimport "qrc:/meti_antifog" as MetiFog',s,count=1,flags=re.M)
        if n!=1 or s.count('Loader {')!=1:raise ValueError('Unexpected video structure')
        s=s.replace('Loader {','Loader {\n id:metiVideoLoader',1)
        marker='//-- Thermal Image'
        if s.count(marker)!=1:raise ValueError('Video insertion anchor missing')
        grids=re.findall(r'(?m)^ {16}Rectangle \{\n(?:.*\n)*? {16}\}',s)
        if len(grids)!=4 or any('_showGrid' not in grid for grid in grids):raise ValueError('Unexpected video grid structure')
        for grid in grids:s=s.replace(grid,'',1)
        overlay='''
MetiFog.AntiFogOverlay {
width:metiVideoLoader.width; height:metiVideoLoader.height
anchors.centerIn:parent
controller:mainWindow
sourceItem:metiVideoLoader.item
streamActive:QGroundControl.videoManager.decoding
sourceKey:_curCameraIndex+":"+_ar+":"+QGroundControl.settingsManager.videoSettings.videoSource.rawValue
}
'''
        overlay+='\nItem {\nwidth:metiVideoLoader.width; height:metiVideoLoader.height\nanchors.centerIn:parent\nvisible:metiVideoLoader.visible\n'+'\n'.join(grids)+'\n}\n'
        s=s.replace(marker,overlay+marker,1)
    return compact(s).encode()

def patch_native(original):
    from elftools.elf.elffile import ELFFile
    result=bytearray(original);changed=[];report=[]
    found={e['path']:e for e in rcc_bundle.entries(original,*TABLE)}
    elf=ELFFile(io.BytesIO(original));symbols={s.name:s for s in elf.get_section_by_name('.dynsym').iter_symbols()}
    for path,symbol in TARGETS.items():
        e=found[path];new=edited_qml(path,e['raw']);bounds,size=replace_payload(result,e,new);changed.append(bounds)
        s=symbols[symbol];section=elf.get_section(s['st_shndx']);off=s['st_value']-section['sh_addr']+section['sh_offset']
        if original[off:off+8]!=b'qv4cdata' or struct.unpack_from('<II',original,off+8)!=(41,0x050f02):raise ValueError('Unexpected compiled QML header')
        struct.pack_into('<I',result,off+8,0);changed.append((off+8,off+12))
        report.append({'resource':path,'old_compressed':e['capacity'],'new_compressed':size,'cache_version_offset':off+8})
    last=0
    for start,end in sorted(changed):
        if result[last:start]!=original[last:start]:raise ValueError('Unexpected native-byte change')
        last=end
    if result[last:]!=original[last:]:raise ValueError('Unexpected trailing native-byte change')
    final={e['path']:e for e in rcc_bundle.entries(result,*TABLE)}
    for path,e in found.items():
        expected=edited_qml(path,e['raw']) if path in TARGETS else e['raw']
        if final[path]['raw']!=expected:raise ValueError('Resource verification failed: '+path)
    return bytes(result),report

def apply(source,decoded,report_path):
    generate_worker()
    data=Path(source).read_bytes();check_source(data);decoded=Path(decoded)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        patched,changes=patch_native(z.read(NATIVE));(decoded/NATIVE).write_bytes(patched)
        existing=rcc_bundle.read(z.read('assets/android_rcc_bundle.rcc'))
    additions=[]
    for f in sorted((Path(__file__).parent/'qml').iterdir()):
        if f.suffix=='.qml' or f.name=='ClassicWorker.bundle.js':additions.append(('/meti_antifog/'+f.name,0,1,f.read_bytes()))
    bundle=rcc_bundle.build(existing+additions)
    if sorted(rcc_bundle.read(bundle))!=sorted(existing+additions):raise ValueError('RCC round-trip failed')
    (decoded/'assets/android_rcc_bundle.rcc').write_bytes(bundle)
    android='{http://schemas.android.com/apk/res/android}';ET.register_namespace('android',android[1:-1])
    manifest=decoded/'AndroidManifest.xml';tree=ET.parse(manifest);root=tree.getroot();root.set('package',PACKAGE)
    application=root.find('application');application.set(android+'label','QGC ANTI-FOG TEST')
    for activity in application.findall('activity'):
        if activity.get(android+'name')=='org.mavlink.qgroundcontrol.QGCActivity':activity.set(android+'label','QGC ANTI-FOG TEST')
    for provider in application.findall('provider'):
        value=provider.get(android+'authorities')
        if value:provider.set(android+'authorities',value.replace('org.mavlink.qgroundcontrol',PACKAGE))
    tree.write(manifest,encoding='utf-8',xml_declaration=True)
    result={'source_sha256':SOURCE_SHA,'package':PACKAGE,'native_changes':changes,'original_rcc_files':len(existing),'added_rcc_files':len(additions),'full_frame':True,'default_enabled':False}
    Path(report_path).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source');p.add_argument('decoded');p.add_argument('report');a=p.parse_args();apply(a.source,a.decoded,a.report)
