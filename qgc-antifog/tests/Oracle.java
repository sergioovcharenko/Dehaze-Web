package ua.dehaze.live;
import java.nio.file.*;
import java.io.*;
public final class Oracle {
 public static void main(String[] args)throws Exception {
  byte[] top=Files.readAllBytes(Paths.get(args[0])),bottom=new byte[top.length];
  for(int y=0;y<108;y++)System.arraycopy(top,y*192*4,bottom,(107-y)*192*4,192*4);
  VideoDehazeProcessor.Result r=VideoDehazeProcessor.process(bottom,null);
  try(DataOutputStream o=new DataOutputStream(new FileOutputStream(args[1]))){
   o.writeFloat(r.ar);o.writeFloat(r.ag);o.writeFloat(r.ab);o.writeFloat(r.haze);o.writeFloat(r.mean);
   for(int y=107;y>=0;y--)o.write(r.map,y*192*4,192*4);
   for(int y=5;y>=0;y--)o.write(r.lut,y*8*256*4,8*256*4);
  }
 }
}
