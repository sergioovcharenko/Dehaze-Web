import unittest,time
from pathlib import Path
class QtTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from PyQt5.QtGui import QGuiApplication
        cls.app=QGuiApplication.instance() or QGuiApplication([])
    def view(self):
        from PyQt5.QtQuick import QQuickView
        from PyQt5.QtCore import QUrl,QObject
        from PyQt5.QtTest import QTest
        v=QQuickView();v.setSource(QUrl.fromLocalFile(str(Path(__file__).with_name('Harness.qml').resolve())))
        self.assertEqual(v.status(),QQuickView.Ready,str(v.errors()));v.show();self.addCleanup(v.close);QTest.qWait(150)
        return v,v.rootObject(),v.rootObject().findChild(QObject,'effect')
    def until(self,condition,seconds=8):
        from PyQt5.QtTest import QTest
        end=time.monotonic()+seconds
        while not condition() and time.monotonic()<end:QTest.qWait(10)
        self.assertTrue(condition(),'Condition timed out')
    def test_off_discards_worker_and_source_restart_resets(self):
        from PyQt5.QtTest import QTest
        v,r,fx=self.view();original=v.grabWindow();r.setProperty('enabledFog',True)
        self.until(lambda:fx.property('workerBusy'))
        r.setProperty('enabledFog',False);self.until(lambda:not fx.property('workerBusy'));QTest.qWait(150)
        self.assertFalse(fx.property('applyEffect'));self.assertEqual(original,v.grabWindow())
        ticket=fx.property('ticket');QTest.qWait(750);self.assertEqual(ticket,fx.property('ticket'))
        r.setProperty('enabledFog',True);self.until(lambda:fx.property('applyEffect'))
        old=fx.property('epoch');r.setProperty('cameraKey','camera2')
        self.assertGreater(fx.property('epoch'),old);self.assertFalse(fx.property('validMap'))
        try:self.until(lambda:fx.property('applyEffect'))
        except AssertionError:
            self.fail(str({k:fx.property(k) for k in ['isOn','busy','workerBusy','validMap','gpuError','epoch','mapEpoch','mapPainted','lutPainted','mapSampled','clock','sampleUrl']})+' '+r.property('errorFog'))
        r.setProperty('streamActive',False);QTest.qWait(100)
        self.assertFalse(fx.property('applyEffect'));self.assertEqual(original,v.grabWindow())
        r.setProperty('streamActive',True);self.until(lambda:fx.property('applyEffect'))
        fx.setProperty('activeApplication',False);QTest.qWait(100)
        self.assertFalse(fx.property('applyEffect'));self.assertEqual(original,v.grabWindow())
    def test_current_video_keeps_moving(self):
        from PyQt5.QtTest import QTest
        v,r,fx=self.view();r.setProperty('moving',True);r.setProperty('enabledFog',True)
        self.until(lambda:fx.property('applyEffect'));QTest.qWait(150);a=v.grabWindow();QTest.qWait(160)
        self.assertTrue(fx.property('applyEffect'));self.assertNotEqual(a,v.grabWindow())
    def test_gpu_failure_latches_and_preserves_original(self):
        from PyQt5.QtCore import QObject,QByteArray
        from PyQt5.QtTest import QTest
        v,r,fx=self.view();original=v.grabWindow()
        shader=next(o for o in fx.findChildren(QObject) if o.metaObject().indexOfProperty('fragmentShader')>=0)
        shader.setProperty('fragmentShader',QByteArray(b'invalid shader deliberate test'))
        r.setProperty('enabledFog',True);self.until(lambda:bool(r.property('errorFog')));QTest.qWait(150)
        self.assertFalse(fx.property('applyEffect'));self.assertEqual(original,v.grabWindow())
        ticket=fx.property('ticket');QTest.qWait(1000);self.assertEqual(ticket,fx.property('ticket'))
        r.setProperty('enabledFog',False);QTest.qWait(50);self.assertEqual('',r.property('errorFog'))
    def test_effect_changes_pixels_and_off_restores_original(self):
        self.assertTrue((Path(__file__).parents[1]/'qml/AntiFogOverlay.qml').exists(),'Video effect is missing')
        from PyQt5.QtQuick import QQuickView
        from PyQt5.QtCore import QUrl,QObject
        from PyQt5.QtTest import QTest
        v=QQuickView();v.setSource(QUrl.fromLocalFile(str(Path(__file__).with_name('Harness.qml').resolve())))
        self.assertEqual(v.status(),QQuickView.Ready,str(v.errors()));v.show();QTest.qWait(200)
        root=v.rootObject();fx=root.findChild(QObject,'effect');before=v.grabWindow()
        root.setProperty('enabledFog',True)
        end=time.monotonic()+12
        while not fx.property('applyEffect') and time.monotonic()<end:QTest.qWait(50)
        diagnostic={k:fx.property(k) for k in ['isOn','busy','workerBusy','validMap','epoch','mapEpoch','mapPainted','lutPainted','mapSampled','clock','sampleUrl']}
        diagnostic['error']=root.property('errorFog')
        self.assertTrue(fx.property('applyEffect'),'No valid processed map '+str(diagnostic));QTest.qWait(100)
        shaderInfo=[(o.metaObject().className(),o.property('status'),o.property('log')) for o in fx.findChildren(QObject) if o.metaObject().indexOfProperty('fragmentShader')>=0]
        self.assertEqual(root.property('errorFog'),'','Shader failed after map upload '+str(shaderInfo))
        self.assertTrue(fx.property('applyEffect'),'Effect fell back while rendering')
        after=v.grabWindow();self.assertFalse(before==after,'Filter did not change pixels')
        root.setProperty('enabledFog',False);QTest.qWait(150)
        self.assertFalse(fx.property('applyEffect'));self.assertTrue(before==v.grabWindow(),'OFF is not original')
        QTest.qWait(1600);self.assertFalse(fx.property('applyEffect'),'Stale result re-enabled effect');v.close()
if __name__=='__main__':unittest.main()
