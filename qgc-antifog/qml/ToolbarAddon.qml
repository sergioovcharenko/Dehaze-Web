/* Derived from the toolbar in the user-supplied QGC APK.
 * QGroundControl project copyright 2009-2020; original licensing retained. */
import QtQuick 2.12
import QGroundControl 1.0
import QGroundControl.ScreenTools 1.0
Row {
    id:indicatorRow
    anchors.top:parent.top;anchors.bottom:parent.bottom
    anchors.margins:_toolIndicatorMargins
    spacing:ScreenTools.defaultFontPixelWidth*1.5
    property var controller:mainWindow
    property var _activeVehicle:QGroundControl.multiVehicleManager.activeVehicle
    property real _toolIndicatorMargins:ScreenTools.defaultFontPixelHeight*.66
    function isRssi(url){return String(url).indexOf('RCRSSIIndicator.qml')>=0;}
    function hasRssi(){
        var list=_activeVehicle?_activeVehicle.toolIndicators:[];
        for(var i=0;i<list.length;i++)if(isRssi(list[i]))return true;
        return false;
    }
    function dropMessageIndicatorTool(){toolIndicatorsRepeater.dropMessageIndicatorTool();}
    Repeater {
        model:QGroundControl.corePlugin.toolBarIndicators
        Loader {anchors.top:parent.top;anchors.bottom:parent.bottom;source:modelData;visible:item?item.showIndicator:false}
    }
    Repeater {
        id:toolIndicatorsRepeater
        model:_activeVehicle?_activeVehicle.toolIndicators:[]
        function dropMessageIndicatorTool(){
            for(var i=0;i<count;i++){
                var tool=itemAt(i);
                if(tool&&tool.item&&tool.item.dropMessageIndicator)tool.item.dropMessageIndicator();
            }
        }
        Row {
            anchors.top:parent.top;anchors.bottom:parent.bottom
            spacing:ScreenTools.defaultFontPixelWidth
            property alias item:indicator.item
            visible:indicator.visible||fog.active
            Loader {id:indicator;anchors.top:parent.top;anchors.bottom:parent.bottom;source:modelData;visible:item?item.showIndicator:false}
            Loader {
                id:fog;anchors.top:parent.top;anchors.bottom:parent.bottom
                active:indicatorRow.isRssi(modelData);visible:active
                source:'AntiFogButton.qml';onLoaded:item.controller=indicatorRow.controller
            }
        }
    }
    Loader {
        anchors.top:parent.top;anchors.bottom:parent.bottom
        active:!indicatorRow.hasRssi();visible:active;source:'AntiFogButton.qml'
        onLoaded:item.controller=indicatorRow.controller
    }
    Repeater {
        model:_activeVehicle?_activeVehicle.modeIndicators:[]
        Loader {anchors.top:parent.top;anchors.bottom:parent.bottom;source:modelData;visible:item?item.showIndicator:false}
    }
}
