package com.aliothmoon.maafw.once;
/** The existing model checks extracted for host regression; no relaxed path or hash policy. */
final class MaaOnceModelPolicy {
 public static final long MAX_MODEL_BYTES=24_000_000L;
 private static final String[] NAMES={"det.onnx","rec.onnx","keys.txt"};
 private static final String[] HASHES={"66c0f34caaf432553710fd9973a7134d8cf924db7109310c3d2562dcc39b209d","7dcf6298d77d2a6eb44c1ebeed990826ca895a9c68d955a3eace00763d052949","b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d"};
 public static boolean capacityAllows(int occupied,long usable){return occupied>=0&&occupied<2&&usable>=3*MAX_MODEL_BYTES;}
 public static int count(){return NAMES.length;}
 public static String name(int i){return NAMES[i];}
 public static String expectedHash(int i){return HASHES[i];}
 public static boolean canonicalMatches(String absolute,String canonical){return absolute!=null&&absolute.equals(canonical);}
 public static boolean allowedBytes(long n){return n>=0&&n<MAX_MODEL_BYTES;}
 public static boolean digestMatches(int i,String digest){return HASHES[i].equals(digest);}
 public static int stageBase(int i){if(i<0||i>=NAMES.length)throw new IllegalArgumentException();return 210+i*10;}
}


