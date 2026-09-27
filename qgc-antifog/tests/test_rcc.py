import importlib.util,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
try:import rcc_bundle as rcc
except ImportError:rcc=None

class BundleTests(unittest.TestCase):
    def test_round_trip_binary_unicode_and_nested_files(self):
        self.assertIsNotNone(rcc,'RCC implementation is missing')
        records=[('/meti/Filter.qml',0,1,b'import QtQuick 2.12\nItem {}'),('/meti/тест.txt',0,1,'туман'.encode()),('/x/a.bin',0,1,bytes(range(256))*20)]
        encoded=rcc.build(records)
        self.assertEqual(sorted(records),sorted(rcc.read(encoded)))
    def test_qt_reads_built_bundle(self):
        self.assertIsNotNone(rcc,'RCC implementation is missing')
        from PyQt5.QtCore import QResource,QFile,QIODevice
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'test.rcc';p.write_bytes(rcc.build([('/meti/check.txt',0,1,b'fog test'*100)]))
            self.assertTrue(QResource.registerResource(str(p)))
            try:
                f=QFile(':/meti/check.txt');self.assertTrue(f.open(QIODevice.ReadOnly));self.assertEqual(bytes(f.readAll()),b'fog test'*100);f.close()
            finally:QResource.unregisterResource(str(p))
    def test_rejects_truncated_or_non_resource(self):
        self.assertIsNotNone(rcc,'RCC implementation is missing')
        for b in [b'',b'not rcc',b'qres'+bytes(16)]:
            with self.assertRaises(ValueError):rcc.read(b)

if __name__=='__main__':unittest.main()
