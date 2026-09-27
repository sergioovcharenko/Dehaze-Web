import QtQuick 2.12
Item {
    id:root
    objectName:"antiFogButton"
    property var controller
    implicitWidth:button.width+timing.width+8
    Rectangle {
        id:button;height:parent.height;radius:4
        width:label.implicitWidth+20
        color:!controller||!controller.metiFogEnabled?'#394047':controller.metiFogError?'#803b35':'#2d6d59'
        border.width:1;border.color:'#829093'
        Text {
            id:label;anchors.centerIn:parent;color:'white'
            text:'ANTI-FOG';font.pixelSize:Math.max(12,parent.height*.33);font.bold:true
        }
        MouseArea {anchors.fill:parent;onClicked:if(controller)controller.metiFogEnabled=!controller.metiFogEnabled}
    }
    Text {
        id:timing;anchors.left:button.right;anchors.leftMargin:8;anchors.verticalCenter:parent.verticalCenter
        color:'#e5e9ea';font.pixelSize:Math.max(10,parent.height*.25)
        text:!controller||!controller.metiFogEnabled?'OFF':controller.metiFogError?'помилка':controller.metiFogMode==='CLASSIC AUTO'?'карта: '+controller.metiFogMs+' мс':'аналіз…'
    }
}
