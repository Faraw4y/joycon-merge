package com.joyconmerge;

import android.content.Context;
import android.content.SharedPreferences;

public class Config {
    private static final String PREFS = "joycon_config";
    private final SharedPreferences prefs;

    public Config(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getFuzz()    { return prefs.getInt("fuzz", 256); }
    public int getFlat()    { return prefs.getInt("flat", 4096); }
    public boolean getInvLX() { return prefs.getBoolean("invLX", false); }
    public boolean getInvLY() { return prefs.getBoolean("invLY", false); }
    public boolean getInvRX() { return prefs.getBoolean("invRX", false); }
    public boolean getInvRY() { return prefs.getBoolean("invRY", false); }

    /* Button mappings - input code */
    public int getCodeA() { return prefs.getInt("cA", 304); }
    public int getCodeB() { return prefs.getInt("cB", 305); }
    public int getCodeX() { return prefs.getInt("cX", 307); }
    public int getCodeY() { return prefs.getInt("cY", 308); }

    /* Button mappings - output code */
    public int getMapA() { return prefs.getInt("mA", 0x130); }
    public int getMapB() { return prefs.getInt("mB", 0x131); }
    public int getMapX() { return prefs.getInt("mX", 0x133); }
    public int getMapY() { return prefs.getInt("mY", 0x134); }
    public int getMapR()  { return prefs.getInt("mR",  0x136); }
    public int getMapZR() { return prefs.getInt("mZR", 0x137); }
    public int getMapPlus(){ return prefs.getInt("mPlus", 0x13b); }
    public int getMapR3() { return prefs.getInt("mR3", 0x13d); }
    public int getMapL()  { return prefs.getInt("mL",  0x135); }
    public int getMapZL() { return prefs.getInt("mZL", 0x139); }
    public int getMapMinus(){ return prefs.getInt("mMinus", 0x13a); }
    public int getMapL3() { return prefs.getInt("mL3", 0x13c); }

    public void save(int fuzz, int flat,
                     boolean invLX, boolean invLY, boolean invRX, boolean invRY,
                     int mA, int mB, int mX, int mY,
                     int mR, int mZR, int mPlus, int mR3,
                     int mL, int mZL, int mMinus, int mL3) {
        prefs.edit()
            .putInt("fuzz", fuzz).putInt("flat", flat)
            .putBoolean("invLX", invLX).putBoolean("invLY", invLY)
            .putBoolean("invRX", invRX).putBoolean("invRY", invRY)
            .putInt("mA", mA).putInt("mB", mB)
            .putInt("mX", mX).putInt("mY", mY)
            .putInt("mR", mR).putInt("mZR", mZR)
            .putInt("mPlus", mPlus).putInt("mR3", mR3)
            .putInt("mL", mL).putInt("mZL", mZL)
            .putInt("mMinus", mMinus).putInt("mL3", mL3)
            .apply();
    }
}
