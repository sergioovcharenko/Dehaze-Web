import QtQuick 2.12
import "../qml" as Fog
Item {
    width:640; height:360
    property alias enabledFog: state.metiFogEnabled
    property alias errorFog: state.metiFogError
    property bool streamActive:true
    property bool moving:false
    property string cameraKey:"camera1"
    QtObject {
        id:state
        property bool metiFogEnabled:false
        property int metiFogMs:-1
        property real metiFogStrength:.6
        property string metiFogMode:"OFF"
        property string metiFogError:""
    }
    Item {
        id:original;anchors.fill:parent
        Repeater {
            model:48
            Rectangle {
                width:80;height:60;x:(index%8)*80;y:Math.floor(index/8)*60
                color:Qt.rgba(.58+(index%4)*.08,.62+(index%3)*.08,.64+(index%5)*.06,1)
            }
        }
        Rectangle {id:mover;width:30;height:30;y:120;x:20;color:"#beb0a0"}
        Timer {interval:40;repeat:true;running:moving;onTriggered:mover.x=(mover.x+17)%610}
    }
    Fog.AntiFogOverlay {id:effect;objectName:"effect";anchors.fill:parent;controller:state;sourceItem:original;streamActive:parent.streamActive;sourceKey:parent.cameraKey;activeApplication:true}
}
