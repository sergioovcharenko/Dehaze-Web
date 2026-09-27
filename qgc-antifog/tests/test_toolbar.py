import unittest,tempfile,json
from pathlib import Path
from PyQt5.QtGui import QGuiApplication
from PyQt5.QtCore import QUrl,QObject,QPoint,QPointF
from PyQt5.QtQuick import QQuickView
from PyQt5.QtTest import QTest
from PyQt5.QtCore import Qt
class ToolbarTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.app=QGuiApplication.instance() or QGuiApplication([])
 def test_rssi_placement_fallback_and_toggle(self):
  with tempfile.TemporaryDirectory() as folder:
   p=Path(folder);module=p/'QGroundControl';screen=module/'ScreenTools';screen.mkdir(parents=True)
   (module/'qmldir').write_text('module QGroundControl\nsingleton QGroundControl 1.0 QGroundControl.qml\n')
   (module/'QGroundControl.qml').write_text('pragma Singleton\nimport QtQuick 2.12\nQtObject {property QtObject corePlugin:QtObject{property var toolBarIndicators:[]}; property QtObject multiVehicleManager:QtObject{property var activeVehicle:null}}')
   (screen/'qmldir').write_text('module QGroundControl.ScreenTools\nsingleton ScreenTools 1.0 ScreenTools.qml\n')
   (screen/'ScreenTools.qml').write_text('pragma Singleton\nimport QtQuick 2.12\nQtObject {property int defaultFontPixelWidth:8;property int defaultFontPixelHeight:16}')
   (p/'RCRSSIIndicator.qml').write_text('import QtQuick 2.12\nRectangle {objectName:"rssi";property bool showIndicator:true;implicitWidth:45;color:"gray"}')
   assets=str(Path(__file__).resolve().parents[1]/'qml')
   (p/'ToolbarHarness.qml').write_text('''import QtQuick 2.12
import '''+json.dumps('file://'+assets)+''' as Fog
Item {id:mainWindow;width:800;height:80
property bool metiFogEnabled:false
property string metiFogError:""
property string metiFogMode:"OFF"
property int metiFogMs:10
property bool vehicle:false
Fog.ToolbarAddon {id:toolbar;objectName:"toolbar";controller:mainWindow
_activeVehicle:mainWindow.vehicle?({toolIndicators:[Qt.resolvedUrl("RCRSSIIndicator.qml")],modeIndicators:[]}):null
}
}''')
   v=QQuickView();v.engine().addImportPath(str(p));v.setSource(QUrl.fromLocalFile(str(p/'ToolbarHarness.qml')))
   self.assertEqual(v.status(),QQuickView.Ready,str(v.errors()));v.show();self.addCleanup(v.close);QTest.qWait(100);r=v.rootObject()
   def items(item):
    result=[]
    for child in item.childItems():result+=[child]+items(child)
    return result
   def buttons():return [b for b in items(r) if b.objectName()=='antiFogButton' and b.property('visible')]
   self.assertEqual(len(buttons()),1);self.assertGreater(buttons()[0].width(),80)
   r.setProperty('vehicle',True);QTest.qWait(150);self.assertEqual(len(buttons()),1)
   b=buttons()[0];rssi=next((x for x in items(r) if x.objectName()=='rssi'),None);self.assertIsNotNone(rssi)
   bp=b.mapToScene(QPointF(0,0));rp=rssi.mapToScene(QPointF(rssi.width(),0))
   self.assertGreaterEqual(bp.x(),rp.x());self.assertLess(bp.x()-rp.x(),20)
   QTest.mouseClick(v,Qt.LeftButton,pos=QPoint(int(bp.x()+20),int(bp.y()+15)));self.assertTrue(r.property('metiFogEnabled'))
   QTest.mouseClick(v,Qt.LeftButton,pos=QPoint(int(bp.x()+20),int(bp.y()+15)));self.assertFalse(r.property('metiFogEnabled'))
   r.setProperty('vehicle',False);QTest.qWait(100);self.assertEqual(len(buttons()),1)
if __name__=='__main__':unittest.main()
