var previous=null,lastEpoch=-1;
WorkerScript.onMessage=function(m){
    try {
        if(m.epoch!==lastEpoch){previous=null;lastEpoch=m.epoch;}
        var result=process(m.rgba,previous,m.strength);
        previous=result;
        WorkerScript.sendMessage({epoch:m.epoch,ticket:m.ticket,sampled:m.sampled,result:result});
    } catch(e) {
        previous=null;
        WorkerScript.sendMessage({epoch:m.epoch,ticket:m.ticket,error:String(e)});
    }
};
