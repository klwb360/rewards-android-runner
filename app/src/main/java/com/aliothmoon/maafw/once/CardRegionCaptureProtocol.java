package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;

/** Local-only bounded crop receipt. No full-display or empty-crop request is representable. */
final class CardRegionCaptureProtocol {
    static final int MAX_PIXELS=2*1024*1024, MAX_PNG=10*1024*1024, MAX_HEADER=512;
    static final int MAX_WIRE=MAX_HEADER+4*((MAX_PNG+2)/3)+2;
    static final long MAX_HELPER_MS=5000;
    enum Status { CAPTURED, API_UNAVAILABLE, PERMISSION_DENIED, SHAPE_CHANGED,
        SECURE_OUTPUT_REJECTED, DIMENSION_REJECTED, CAPTURE_NO_BUFFER, CAPTURE_FAILED,
        DEADLINE_EXCEEDED, ENCODE_FAILED, CLEANUP_FAILED }
    enum Cleanup { CONFIRMED, UNKNOWN }
    record Request(String nonce,long deadline,Box roi,int fullWidth,int fullHeight,int rotation) {
        Request {
            if(!NativeUiStructureProtocol.validNonce(nonce)||deadline<0
                    ||!validCrop(roi,fullWidth,fullHeight,rotation))throw invalid();
        }
        String[] arguments(){return new String[]{nonce,Long.toString(deadline),Integer.toString(fullWidth),
                Integer.toString(fullHeight),Integer.toString(rotation),Integer.toString(roi.left()),
                Integer.toString(roi.top()),Integer.toString(roi.right()),Integer.toString(roi.bottom())};}
    }
    record Report(Request request,long started,long finished,Status status,Cleanup cleanup,
                  boolean nativeInvoked,int pngWidth,int pngHeight,byte[] png) {
        Report {
            if(request==null||started<0||finished<started||status==null||cleanup==null)throw invalid();
            if(status==Status.CAPTURED){
                if(!nativeInvoked||cleanup!=Cleanup.CONFIRMED||finished>=request.deadline()
                        ||pngWidth!=request.roi().width()||pngHeight!=request.roi().height()
                        ||!validPng(png,pngWidth,pngHeight))throw invalid();
            }else if(png!=null||pngWidth!=0||pngHeight!=0)throw invalid();
            if(status==Status.CAPTURE_NO_BUFFER&&nativeInvoked&&cleanup!=Cleanup.UNKNOWN)throw invalid();
        }
    }
    static boolean validCrop(Box roi,int width,int height,int rotation){
        return roi!=null&&rotation==0&&width>0&&height>0&&width<=4096&&height<=4096
                &&(long)width*height<=4L*1024*1024&&roi.left()>0&&roi.top()>0
                &&roi.right()<width&&roi.bottom()<height&&roi.width()>=16&&roi.height()>=16
                &&(long)roi.width()*roi.height()<=MAX_PIXELS;
    }
    static Request parseArguments(String[] args,long now){
        return parseArguments(args,now,false);
    }
    static Request parseArguments(String[] args,long now,boolean cleanupReceiptAllowed){
        if(args==null||args.length!=9||now<0)throw invalid();
        Request request=new Request(args[0],number(args[1]),new Box(integer(args[5]),integer(args[6]),
                integer(args[7]),integer(args[8])),integer(args[2]),integer(args[3]),integer(args[4]));
        if(request.deadline()<=now){
            if(!cleanupReceiptAllowed||now-request.deadline()>LiveCropStream.CLEANUP_MS)throw invalid();
        }else if(request.deadline()-now>MAX_HELPER_MS)throw invalid();
        return request;
    }
    static byte[] encode(Report report){
        String header=header(report);byte[] h=header.getBytes(StandardCharsets.US_ASCII),encoded=null;
        try{
            encoded=report.png()==null?new byte[0]:Base64.getEncoder().encode(report.png());
            byte[] wire=new byte[h.length+encoded.length+2];
            System.arraycopy(h,0,wire,0,h.length);wire[h.length]='\n';
            System.arraycopy(encoded,0,wire,h.length+1,encoded.length);wire[wire.length-1]='\n';
            return wire;
        }finally{Arrays.fill(h,(byte)0);if(encoded!=null)Arrays.fill(encoded,(byte)0);}
    }
    private static String header(Report r){
        var q=r.request();var b=q.roi();
        String h="CRP1|"+q.nonce()+"|"+r.started()+"|"+r.finished()+"|"+q.deadline()+"|"
                +q.fullWidth()+"|"+q.fullHeight()+"|"+q.rotation()+"|"+b.left()+"|"+b.top()+"|"+b.right()+"|"+b.bottom()
                +"|"+r.status()+"|"+r.cleanup()+"|"+(r.nativeInvoked()?1:0)+"|"+(r.png()==null?0:r.png().length)
                +"|"+r.pngWidth()+"|"+r.pngHeight();
        if(h.length()>MAX_HEADER)throw invalid();return h;
    }
    static Report parse(byte[] wire,int count,Request expected,long dispatched,long now){
        if(wire==null||count<3||count>wire.length||count>MAX_WIRE||expected==null
                ||dispatched<0||now<dispatched||wire[count-1]!='\n')throw invalid();
        int end=-1;
        for(int i=0;i<Math.min(count-1,MAX_HEADER+1);i++){
            int c=wire[i]&255;if(c=='\n'){end=i;break;}
            if(!(c>='A'&&c<='Z'||c>='a'&&c<='f'||c>='0'&&c<='9'||c=='_'||c=='|'))throw invalid();
        }
        if(end<1||end>MAX_HEADER)throw invalid();
        String h=new String(wire,0,end,StandardCharsets.US_ASCII);String[] f=h.split("\\|",-1);
        if(f.length!=18||!f[0].equals("CRP1"))throw invalid();
        byte[] payload=null,png=null,canonical=null;boolean adopted=false;
        try{
            Request actual=new Request(f[1],number(f[4]),new Box(integer(f[8]),integer(f[9]),integer(f[10]),integer(f[11])),
                    integer(f[5]),integer(f[6]),integer(f[7]));
            if(!actual.equals(expected))throw invalid();
            long started=number(f[2]),finished=number(f[3]);
            if(started<dispatched||dispatched>=expected.deadline()||finished<started||finished>now)throw invalid();
            Status status=Status.valueOf(f[12]);Cleanup cleanup=Cleanup.valueOf(f[13]);
            int nativeFlag=integer(f[14]),bytes=integer(f[15]),width=integer(f[16]),height=integer(f[17]);
            if(nativeFlag<0||nativeFlag>1||bytes<0||bytes>MAX_PNG)throw invalid();
            // A JVM that started too late can attest only that it did no native work.
            if(started>=expected.deadline() && (status!=Status.DEADLINE_EXCEEDED || cleanup!=Cleanup.CONFIRMED
                    ||nativeFlag!=0||bytes!=0||width!=0||height!=0||finished-expected.deadline()>LiveCropStream.CLEANUP_MS))
                throw invalid();
            int payloadLength=count-end-2;if(payloadLength!=4*((bytes+2)/3))throw invalid();
            payload=Arrays.copyOfRange(wire,end+1,count-1);
            for(byte value:payload){int c=value&255;if(!(c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='+'||c=='/'||c=='='))throw invalid();}
            if(bytes>0){png=Base64.getDecoder().decode(payload);if(png.length!=bytes)throw invalid();
                canonical=Base64.getEncoder().encode(png);if(!Arrays.equals(canonical,payload))throw invalid();}
            Report report=new Report(actual,started,finished,status,cleanup,nativeFlag==1,width,height,png);
            if(!header(report).equals(h))throw invalid();adopted=true;return report;
        }catch(IllegalArgumentException|ArithmeticException failure){throw invalid();}
        finally{if(payload!=null)Arrays.fill(payload,(byte)0);if(canonical!=null)Arrays.fill(canonical,(byte)0);
            if(!adopted&&png!=null)Arrays.fill(png,(byte)0);}
    }
    /** Strict PNG framing/CRC/shape check; no post-capture crop or decoded full-display allocation. */
    static boolean validPng(byte[] png,int width,int height){
        if(png==null||png.length<57||png.length>MAX_PNG||width<16||height<16||(long)width*height>MAX_PIXELS)return false;
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        for(int i=0;i<8;i++)if(png[i]!=signature[i])return false;
        int p=8,dataBytes=0;boolean header=false,data=false,endedData=false;
        while(p<=png.length-12){
            long size=u32(png,p);if(size>MAX_PNG||size>png.length-p-12)return false;
            int n=(int)size,end=p+8+n;String type=new String(png,p+4,4,StandardCharsets.US_ASCII);
            CRC32 crc=new CRC32();crc.update(png,p+4,n+4);if(crc.getValue()!=u32(png,end))return false;
            if(!header){
                if(!type.equals("IHDR")||n!=13||u32(png,p+8)!=width||u32(png,p+12)!=height
                        ||png[p+16]!=8||(png[p+17]!=2&&png[p+17]!=6)||png[p+18]!=0||png[p+19]!=0||png[p+20]!=0)return false;
                header=true;
            }else if(type.equals("IDAT")){if(endedData)return false;data=true;dataBytes+=n;}
            else if(type.equals("IEND"))return data&&dataBytes>0&&n==0&&end+4==png.length;
            else{
                if(data)endedData=true;
                if(!(type.equals("sRGB")&&n==1||type.equals("gAMA")&&n==4||type.equals("cHRM")&&n==32
                        ||type.equals("sBIT")&&n>=3&&n<=4||type.equals("pHYs")&&n==9))return false;
            }
            p=end+4;
        }
        return false;
    }
    private static long u32(byte[] b,int p){return ((b[p]&255L)<<24)|((b[p+1]&255L)<<16)|((b[p+2]&255L)<<8)|(b[p+3]&255L);}
    private static long number(String text){return NativeUiStructureProtocol.number(text);}
    private static int integer(String text){return Math.toIntExact(number(text));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid fixed card crop receipt");}
    private CardRegionCaptureProtocol(){}
}
