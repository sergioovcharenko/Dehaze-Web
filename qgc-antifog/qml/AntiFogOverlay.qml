import QtQuick 2.12

Item {
    id:root
    property var controller
    property Item sourceItem
    property bool streamActive:false
    property string sourceKey:""
    property bool activeApplication:Qt.application.state===Qt.ApplicationActive
    readonly property bool isOn:!!controller && controller.metiFogEnabled && streamActive && activeApplication && !!sourceItem && visible
    readonly property bool applyEffect:isOn && validMap && clock-mapSampled<2000 && effect.status!==ShaderEffect.Error
    property bool validMap:false
    property bool busy:false
    property bool workerBusy:false
    property int epoch:0
    property int ticket:0
    property double clock:Date.now()
    property double jobStarted:0
    property double mapSampled:0
    property var snapshot:null
    property string sampleUrl:""
    property var mapResult:null
    property int mapEpoch:-1
    property int mapPainted:-1
    property int lutPainted:-1
    property vector3d air:Qt.vector3d(.84,.84,.84)
    property real appliedStrength:.6
    property string gpuError:""

    function invalidate() {
        epoch++;validMap=false;mapPainted=-1;lutPainted=-1;mapEpoch=-1;
        if(sampleUrl!==''){sampler.unloadImage(sampleUrl);sampleUrl='';}snapshot=null;
        if(!workerBusy){busy=false;ticket++;}
        if(controller){controller.metiFogMs=-1;controller.metiFogError=isOn?gpuError:'';controller.metiFogMode=isOn?(gpuError?'ERROR':'WAIT'):'OFF';}
    }
    function fail(message) {
        validMap=false;
        if(controller){controller.metiFogError=message;controller.metiFogMs=-1;controller.metiFogMode='ERROR';}
    }
    function paintReady() {
        if(isOn && !gpuError && mapEpoch===epoch && mapPainted===epoch && lutPainted===epoch && clock-mapSampled<2000) {
            validMap=true;controller.metiFogMode='CLASSIC AUTO';
        }
    }
    function requestAnalysis() {
        if(!isOn||gpuError||busy||!sampler.available)return;
        busy=true;var e=epoch,t=++ticket;jobStarted=Date.now();var sampled=jobStarted;
        var accepted=sourceItem.grabToImage(function(result){
            if(e!==epoch||t!==ticket||!isOn)return;
            if(!result||!result.url){busy=false;fail('Не вдалося отримати кадр');return;}
            snapshot=result;sampler.jobEpoch=e;sampler.jobTicket=t;sampler.sampled=sampled;
            sampleUrl=result.url.toString();sampler.loadImage(sampleUrl);
            if(sampler.isImageLoaded(sampleUrl))sampler.consume();
        },Qt.size(192,108));
        if(!accepted){busy=false;fail('Відеокадр недоступний');}
    }
    onIsOnChanged:{invalidate();if(isOn)Qt.callLater(requestAnalysis);}
    onSourceItemChanged:invalidate()
    onSourceKeyChanged:invalidate()
    onWidthChanged:invalidate()
    onHeightChanged:invalidate()
    onApplyEffectChanged:if(controller&&!applyEffect&&!controller.metiFogError)controller.metiFogMode=isOn?'WAIT':'OFF'

    Timer {
        interval:100;running:root.isOn;repeat:true
        onTriggered:{
            root.clock=Date.now();
            if(root.busy&&!root.workerBusy&&root.clock-root.jobStarted>2000){root.ticket++;root.busy=false;root.fail('Час отримання кадру перевищено');}
        }
    }
    Timer {interval:650;running:root.isOn;repeat:true;triggeredOnStart:true;onTriggered:root.requestAnalysis()}
    Canvas {
        id:sampler;width:192;height:108;visible:false
        property int jobEpoch:-1
        property int jobTicket:-1
        property double sampled:0
        onImageLoaded:consume()
        function consume(){
            if(!root.isOn||root.workerBusy||jobEpoch!==root.epoch||jobTicket!==root.ticket||root.sampleUrl==='')return;
            try {
                var ctx=getContext('2d');ctx.clearRect(0,0,192,108);ctx.drawImage(root.sampleUrl,0,0,192,108);
                var image=ctx.getImageData(0,0,192,108),rgba=new Array(192*108*4);
                for(var i=0;i<rgba.length;i++)rgba[i]=image.data[i];
                root.workerBusy=true;
                worker.sendMessage({epoch:jobEpoch,ticket:jobTicket,sampled:sampled,rgba:rgba,strength:root.controller.metiFogStrength});
                unloadImage(root.sampleUrl);root.sampleUrl='';root.snapshot=null;
            } catch(e){root.busy=false;root.workerBusy=false;root.fail(String(e));}
        }
    }
    WorkerScript {
        id:worker;source:'ClassicWorker.bundle.js'
        onMessage:{
            root.workerBusy=false;root.busy=false;
            if(!root.isOn||root.gpuError||messageObject.epoch!==root.epoch||messageObject.ticket!==root.ticket)return;
            if(messageObject.error){root.fail(messageObject.error);return;}
            root.clock=Date.now();
            if(root.clock-messageObject.sampled>=2000){root.fail('Карта обробки застаріла');return;}
            var r=messageObject.result;root.mapResult=r;root.mapEpoch=root.epoch;
            root.mapPainted=-1;root.lutPainted=-1;root.mapSampled=messageObject.sampled;
            root.air=Qt.vector3d(r.ar,r.ag,r.ab);root.appliedStrength=r.strength;
            root.controller.metiFogStrength=r.strength;root.controller.metiFogMs=r.ms;root.controller.metiFogError='';
            mapCanvas.requestPaint();lutCanvas.requestPaint();
        }
    }
    Canvas {
        id:mapCanvas;width:192;height:216;visible:root.isOn
        onPaint:{
            if(!root.mapResult||root.mapEpoch!==root.epoch)return;
            var ctx=getContext('2d'),im=ctx.createImageData(192,216),m=root.mapResult.map,n=192*108*4;
            for(var i=0;i<n;i+=4){
                im.data[i]=m[i];im.data[i+1]=m[i+1];im.data[i+2]=m[i+2];im.data[i+3]=255;
                im.data[n+i]=im.data[n+i+1]=im.data[n+i+2]=m[i+3];im.data[n+i+3]=255;
            }
            ctx.putImageData(im,0,0);
            root.mapPainted=root.mapEpoch;root.paintReady();
        }
    }
    Canvas {
        id:lutCanvas;width:256;height:48;visible:root.isOn
        onPaint:{
            if(!root.mapResult||root.mapEpoch!==root.epoch)return;
            var ctx=getContext('2d'),im=ctx.createImageData(256,48),m=root.mapResult.lut;
            for(var i=0;i<m.length;i++)im.data[i]=m[i];ctx.putImageData(im,0,0);
            root.lutPainted=root.mapEpoch;root.paintReady();
        }
    }
    ShaderEffectSource {id:videoTexture;sourceItem:root.isOn?root.sourceItem:null;hideSource:effect.visible;live:root.isOn;visible:false}
    ShaderEffectSource {id:mapTexture;sourceItem:root.isOn?mapCanvas:null;hideSource:true;live:root.isOn;visible:false;smooth:true}
    ShaderEffectSource {id:lutTexture;sourceItem:root.isOn?lutCanvas:null;hideSource:true;live:root.isOn;visible:false;smooth:false}
    ClassicShader {
        id:effect;anchors.fill:parent;visible:root.isOn&&!root.gpuError
        uEnhanced:root.applyEffect?1:0
        source:videoTexture;uTransmission:mapTexture;uClahe:lutTexture;uAir:root.air;uStrength:root.appliedStrength
        uPixel:Qt.vector2d(1/Math.max(1,root.width),1/Math.max(1,root.height))
        onStatusChanged:if(status===ShaderEffect.Error){root.gpuError='Помилка GPU: '+log;root.invalidate();root.fail(root.gpuError);}
    }
}
