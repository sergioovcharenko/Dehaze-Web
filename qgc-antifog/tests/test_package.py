import unittest,struct,sys,tempfile,zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from package_apk import rename_table,signature_entry,PACKAGE,recompose,NATIVE
class PackageTests(unittest.TestCase):
    def test_only_name_changes(self):
        package=struct.pack('<HHII',0x200,288,288,127)+'org.mavlink.qgroundcontrol'.encode('utf-16le').ljust(256,b'\0')+bytes(20)
        raw=struct.pack('<HHII',2,12,300,1)+package
        patched=rename_table(raw)
        self.assertEqual(raw[:24],patched[:24]);self.assertEqual(raw[280:],patched[280:])
        self.assertEqual(patched[24:280].decode('utf-16le').rstrip('\0'),PACKAGE)
        with self.assertRaises(ValueError):rename_table(patched)
    def test_signature_removal_is_narrow(self):
        for name in ['META-INF/CERT.RSA','META-INF/CERT.SF','META-INF/MANIFEST.MF']:self.assertTrue(signature_entry(name))
        for name in ['classes.dex','META-INF/services/example','assets/file.sf']:self.assertFalse(signature_entry(name))
    def test_recompose_preserves_original_entries(self):
        from unittest.mock import patch
        package=struct.pack('<HHII',0x200,288,288,127)+'org.mavlink.qgroundcontrol'.encode('utf-16le').ljust(256,b'\0')+bytes(20)
        table=struct.pack('<HHII',2,12,300,1)+package
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);decoded=p/'decoded';(decoded/Path(NATIVE).parent).mkdir(parents=True);(decoded/'assets').mkdir()
            (decoded/NATIVE).write_bytes(b'new-native');(decoded/'assets/android_rcc_bundle.rcc').write_bytes(b'new-rcc')
            with zipfile.ZipFile(p/'source.apk','w') as z:
                for name,data in [('AndroidManifest.xml',b'old-manifest'),('resources.arsc',table),(NATIVE,b'old-native'),('assets/android_rcc_bundle.rcc',b'old-rcc'),('classes.dex',b'dex-unchanged'),('res/keep.xml',b'resource-unchanged'),('META-INF/CERT.RSA',b'old-signature')]:z.writestr(name,data)
            with zipfile.ZipFile(p/'rebuilt.apk','w') as z:z.writestr('AndroidManifest.xml',b'new-manifest-with-different-length')
            with patch('package_apk.check_source'):
                recompose(p/'source.apk',p/'rebuilt.apk',decoded,p/'out.apk',p/'report.json')
            with zipfile.ZipFile(p/'out.apk') as z:self.assertEqual(z.read('classes.dex'),b'dex-unchanged');self.assertNotIn('META-INF/CERT.RSA',z.namelist())
if __name__=='__main__':unittest.main()
