package com.aliothmoon.maafw.once;

import java.util.function.BooleanSupplier;

/** The outer visibility result must be accepted before observation counts can advance. */
final class OverviewPublicationCommit {
    static boolean commit(boolean callbackSaved,boolean outerAccepted,BooleanSupplier stillCurrent,Runnable accepted){
        if(!callbackSaved||!outerAccepted||stillCurrent==null||accepted==null)return false;
        try{if(!stillCurrent.getAsBoolean())return false;accepted.run();return true;}
        catch(RuntimeException rejected){return false;}
    }
    private OverviewPublicationCommit(){}
}
