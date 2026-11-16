package com.aliothmoon.maafw.once;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Stable local digests. Raw title/description is never persisted by this class. */
final class LiveCardIdentity {
    static String normalize(String text){return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC)
            .replaceAll("[\\u200B-\\u200D\\uFEFF]","").replaceAll("[\\s\\p{Z}]+"," ").trim().toLowerCase(java.util.Locale.ROOT);}
    static String hashText(String text){return CardNativeMockReceipt.digest(normalize(text).getBytes(StandardCharsets.UTF_8));}
    static String descriptor(String value){
        if(value==null||value.length()>2048)throw new IllegalArgumentException("Bounded descriptor required");
        String stable=normalize(value).replaceFirst("(?:[,，]\\s*)?\\+?\\s*[1-9][0-9]{0,3}\\s*积分$","");
        if(stable.isEmpty())throw new IllegalArgumentException("Nonempty descriptor required");
        return hashText(stable);
    }
    private LiveCardIdentity(){}
}
