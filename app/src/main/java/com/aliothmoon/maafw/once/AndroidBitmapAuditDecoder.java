package com.aliothmoon.maafw.once;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Build;

/** One synchronous software decode in the normal-UID source owner. No raw output. */
final class AndroidBitmapAuditDecoder implements PngImageAudit.Decoder {
    @Override public PngImageAudit.Image decode(byte[] encoded,int length){
        if(Build.VERSION.SDK_INT<34||android.os.Process.myUid()<10000)throw new SecurityException("Normal UID API34 required");
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inMutable=true;options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;
        options.inScaled=false;options.inDensity=0;options.inTargetDensity=0;options.inBitmap=null;
        options.inPremultiplied=false;options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap bitmap=BitmapFactory.decodeByteArray(encoded,0,length,options);
        return bitmap==null?null:new OwnedBitmap(bitmap);
    }
    private static final class OwnedBitmap implements PngImageAudit.Image {
        private final Bitmap bitmap;
        private boolean erased,recycled;
        OwnedBitmap(Bitmap bitmap){this.bitmap=bitmap;}
        public PngImageAudit.Facts facts(){
            if(Build.VERSION.SDK_INT<34)throw new SecurityException();
            Bitmap.Config c=bitmap.getConfig();ColorSpace cs=bitmap.getColorSpace();
            PngImageAudit.Config config=c==Bitmap.Config.ARGB_8888?PngImageAudit.Config.ARGB_8888:c==Bitmap.Config.HARDWARE?PngImageAudit.Config.HARDWARE:PngImageAudit.Config.OTHER;
            return new PngImageAudit.Facts(bitmap.getWidth(),bitmap.getHeight(),config,cs==null?-1:cs.getId(),bitmap.isMutable(),bitmap.hasAlpha(),bitmap.isPremultiplied(),cs!=null&&cs.isSrgb(),cs!=null&&cs.isWideGamut());
        }
        public void argbRow(int y,int[] row){bitmap.getPixels(row,0,row.length,0,y,row.length,1);}
        public void erase(){if(erased||recycled||!bitmap.isMutable())throw new IllegalStateException();erased=true;bitmap.eraseColor(0);}
        public void recycle(){if(recycled)throw new IllegalStateException();recycled=true;bitmap.recycle();}
    }
}
