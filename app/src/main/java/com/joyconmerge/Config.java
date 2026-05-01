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

    /* Button mappings - output code (defaults match Nintendo physical layout) */
    public int getMapA() { return prefs.getInt("mA", 0x131); }  // BTN_EAST
    public int getMapB() { return prefs.getInt("mB", 0x130); }  // BTN_SOUTH
    public int getMapX() { return prefs.getInt("mX", 0x134); }  // BTN_WEST  (Nintendo X = west position)
    public int getMapY() { return prefs.getInt("mY", 0x133); }  // BTN_NORTH (Nintendo Y = north position)
    public int getMapR()  { return prefs.getInt("mR",  0x137); }
    public int getMapZR() { return prefs.getInt("mZR", 0x139); }
    public int getMapPlus(){ return prefs.getInt("mPlus", 0x13b); }
    public int getMapR3() { return prefs.getInt("mR3", 0x13e); }
    public int getMapL()  { return prefs.getInt("mL",  0x136); }
    public int getMapZL() { return prefs.getInt("mZL", 0x138); }
    public int getMapMinus(){ return prefs.getInt("mMinus", 0x13a); }
    public int getMapL3() { return prefs.getInt("mL3", 0x13d); }
    public int getMapHome(){ return prefs.getInt("mHome", 0x13c); }
    public int getMapCapture(){ return prefs.getInt("mCapture", 0xa7); }

    public void save(int fuzz, int flat,
                     boolean invLX, boolean invLY, boolean invRX, boolean invRY,
                     int mA, int mB, int mX, int mY,
                     int mR, int mZR, int mPlus, int mR3,
                     int mL, int mZL, int mMinus, int mL3,
                     int mHome, int mCapture) {
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
            .putInt("mHome", mHome)
            .putInt("mCapture", mCapture)
            .apply();
    }
}
