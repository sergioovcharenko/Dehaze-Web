import sys,unittest,os,zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
try:import patch_apk as patch
except ImportError:patch=None

class PatchTests(unittest.TestCase):
    def test_bounded_resource_patch_and_overflow_rejected(self):
        self.assertIsNotNone(patch,'Patcher missing')
        import zlib,struct
        old=b'old\n'*100;payload=struct.pack('>I',len(old))+zlib.compress(old,9);b=bytearray(b'prefix'+struct.pack('>I',len(payload))+payload+b'suffix')
        e={'payload':6,'capacity':len(payload),'flags':1}
        patch.replace_payload(b,e,b'new')
        n=struct.unpack_from('>I',b,6)[0]
        self.assertEqual(zlib.decompress(b[14:10+n]),b'new');self.assertTrue(b.endswith(b'suffix'))
        with self.assertRaises(ValueError):patch.replace_payload(b,e,bytes(range(256))*20)
    def test_wrong_apk_is_rejected(self):
        self.assertIsNotNone(patch,'Patcher missing')
        with self.assertRaises(ValueError):patch.check_source(b'not the uploaded APK')
    @unittest.skipUnless(os.environ.get('QGC_SOURCE_APK'),'Exact user APK needed for native resource integration')
    def test_video_grid_is_outside_processed_source(self):
        with zipfile.ZipFile(os.environ['QGC_SOURCE_APK']) as z:original=z.read(patch.NATIVE)
        found={e['path']:e for e in patch.rcc_bundle.entries(original,*patch.TABLE)}
        path='/qml/QGroundControl/FlightDisplay/FlightDisplayViewVideo.qml'
        edited=patch.edited_qml(path,found[path]['raw']).decode()
        before,after=edited.split('MetiFog.AntiFogOverlay',1)
        self.assertNotIn('visible: _showGrid',before)
        self.assertEqual(after.count('visible: _showGrid'),4)

if __name__=='__main__':unittest.main()
