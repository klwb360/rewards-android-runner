package com.aliothmoon.maafw.once;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Local same-frame diagrams only. Called after source/image publication gates, never dispatches input. */
final class OverviewReviewArtifacts {
    record Saved(int regions,int points,String manifest) { }
    private static final int HEADER=144;
    private static Object directoryIdentity(Path path)throws IOException{
        if(path==null||!path.isAbsolute()||!path.normalize().equals(path))throw new IOException("Exact local directory required");
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null)throw new IOException("Local directory identity unavailable");
        return a.fileKey();
    }
    private static void same(Path path,Object identity)throws IOException{
        if(!identity.equals(directoryIdentity(path)))throw new IOException("Local output directory changed");
    }
    static Saved write(Path directory,int frameIndex,OverviewFrameResult result,OverviewFramePreview preview)throws Exception{
        if(frameIndex<1||frameIndex>12||result==null||result.frameId()!=frameIndex||preview==null||result.failed())
            throw new IOException("Qualified bounded frame result required");
        Object identity=directoryIdentity(directory);
        return preview.withPixels((width,height,pixels)->writePixels(directory,identity,frameIndex,result,width,height,pixels));
    }
    private static Saved writePixels(Path directory,Object identity,int frameIndex,OverviewFrameResult result,
            int width,int height,int[] pixels)throws IOException{
        var groups=new LinkedHashMap<Integer,List<OverviewFrameResult.Item>>();
        for(var item:result.items())groups.computeIfAbsent(item.regionIndex(),unused->new ArrayList<>()).add(item);
        var points=OverviewMockPlan.points(result,true);
        StringBuilder json=new StringBuilder("{\"version\":1,\"frame\":").append(frameIndex)
                .append(",\"sameCapturedFrame\":true,\"mockDispatched\":0,\"earnedPointsVerified\":false,\"regions\":[");
        boolean first=true;
        for(var group:groups.values()){
            var initial=group.get(0);Box crop=initial.coarse();
            if(crop.left()<0||crop.top()<0||crop.right()>width||crop.bottom()>height||crop.width()<1||crop.height()<1)
                throw new IOException("Crop outside original frame");
            for(var item:group)if(item.role()!=initial.role()||!item.coarse().equals(crop))throw new IOException("Mixed region identity");
            int canvasWidth=Math.max(900,crop.width());
            Bitmap image=Bitmap.createBitmap(canvasWidth,crop.height()+HEADER,Bitmap.Config.ARGB_8888);
            try{
                image.eraseColor(Color.WHITE);
                image.setPixels(pixels,crop.top()*width+crop.left(),width,0,HEADER,crop.width(),crop.height());
                Canvas canvas=new Canvas(image);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.rgb(25,35,50));paint.setTextSize(27);
                canvas.drawText(String.format(Locale.ROOT,"帧 %02d · 区域 %02d · %s",frameIndex,initial.regionIndex()+1,label(initial.role())),16,34,paint);
                paint.setTextSize(21);canvas.drawText("红点仅模拟，未执行；灰色为已完成跳过；黄色为待核对。",16,69,paint);
                canvas.drawText("同次捕获截图的区域裁图，不代表任务已执行或积分到账。",16,102,paint);
                canvas.drawText("搜索与阅读后置；坐标仅供本机审查。",16,130,paint);
                for(var item:group){
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);
                    paint.setColor(item.status()==OverviewFrameResult.Status.HOLD?Color.rgb(200,140,0):
                            item.status()==OverviewFrameResult.Status.ITEM_COMPLETE_SKIP||item.status()==OverviewFrameResult.Status.REGION_COMPLETE_SKIP
                                    ?Color.rgb(105,105,105):Color.rgb(0,140,110));
                    Box border=item.boundary()==null?item.coarse():item.boundary();
                    if(!crop.contains(border))throw new IOException("Foreign boundary in local diagram");
                    box(canvas,paint,border,crop);paint.setStyle(Paint.Style.FILL);paint.setTextSize(18);
                    canvas.drawText(item.status().name()+" / "+item.reason().name(),
                            Math.max(4,border.left()-crop.left()+5),Math.max(HEADER+23,border.top()-crop.top()+HEADER+23),paint);
                }
                for(var point:points)if(point.regionIndex()==initial.regionIndex()){
                    float x=point.x()-crop.left(),y=point.y()-crop.top()+HEADER;
                    paint.setColor(Color.rgb(220,30,50));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);
                    canvas.drawCircle(x,y,18,paint);canvas.drawLine(x-25,y,x+25,y,paint);canvas.drawLine(x,y-25,x,y+25,paint);
                    paint.setStyle(Paint.Style.FILL);paint.setTextSize(24);canvas.drawText(Integer.toString(point.number()),x+27,y+8,paint);
                }
                String name=String.format(Locale.ROOT,"F%02d_R%02d_%s.png",frameIndex,initial.regionIndex()+1,initial.role().name());
                same(directory,identity);
                try(FileChannel file=FileChannel.open(directory.resolve(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    if(!image.compress(Bitmap.CompressFormat.PNG,100,Channels.newOutputStream(file)))throw new IOException("PNG export failed");
                    file.force(true);
                }
                same(directory,identity);
                if(!first)json.append(',');first=false;
                json.append("{\"region\":").append(initial.regionIndex()).append(",\"role\":\"").append(initial.role().name())
                        .append("\",\"image\":\"").append(name).append("\",\"items\":[");
                for(int i=0;i<group.size();i++){
                    var item=group.get(i);if(i>0)json.append(',');
                    json.append("{\"item\":").append(item.itemIndex()).append(",\"status\":\"").append(item.status().name())
                            .append("\",\"reason\":\"").append(item.reason().name()).append("\",\"points\":").append(item.displayedPoints())
                            .append(",\"current\":").append(item.current()).append(",\"total\":").append(item.total())
                            .append(",\"mockPoint\":");
                    var point=points.stream().filter(p->p.regionIndex()==item.regionIndex()&&p.itemIndex()==item.itemIndex()).findFirst();
                    if(point.isPresent())json.append('[').append(point.get().x()).append(',').append(point.get().y()).append(']');else json.append("null");
                    json.append('}');
                }
                json.append("]}");
            }finally{image.eraseColor(0);image.recycle();}
        }
        json.append("]}");String manifest=String.format(Locale.ROOT,"F%02d.json",frameIndex);
        same(directory,identity);
        try(FileChannel file=FileChannel.open(directory.resolve(manifest),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            java.nio.ByteBuffer bytes=StandardCharsets.UTF_8.encode(json.toString());
            while(bytes.hasRemaining())if(file.write(bytes)<=0)throw new IOException("Local manifest write unconfirmed");file.force(true);
        }
        same(directory,identity);try(FileChannel dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}
        return new Saved(groups.size(),points.size(),manifest);
    }
    private static void box(Canvas c,Paint p,Box b,Box crop){c.drawRect(b.left()-crop.left(),b.top()-crop.top()+HEADER,b.right()-crop.left()-1,b.bottom()-crop.top()+HEADER-1,p);}
    private static String label(OverviewUiRegions.Role role){return switch(role){
        case CHECK_IN->"签到";case DAILY_PROGRESS->"每日活动周进度";case DAILY_ACTIVITIES->"每日活动卡片";
        case MORE_ACTIVITIES->"更多活动";case POINTS_SUMMARY->"今日积分";case STREAK_SUMMARY->"连续进度";
    };}
    private OverviewReviewArtifacts(){}
}
