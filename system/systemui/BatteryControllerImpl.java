package com.android.systemui.statusbar.policy;

import com.android.settingslib.fuelgauge.BatterySaverUtils;
import android.net.Uri;
import com.oneplus.util.OpUtils;
import android.content.Intent;
import android.os.Build;
import java.io.PrintWriter;
import java.io.FileDescriptor;
import android.os.Bundle;
import com.android.systemui.Dependency;
import android.content.IntentFilter;
import java.util.Iterator;
import com.android.settingslib.utils.PowerUtil;
import java.text.NumberFormat;
import com.android.internal.annotations.VisibleForTesting;
import android.util.Log;
import android.os.PowerManager;
import android.os.Handler;
import com.android.systemui.power.EnhancedEstimates;
import com.android.settingslib.fuelgauge.Estimate;
import android.content.Context;
import java.util.ArrayList;
import android.content.BroadcastReceiver;

public class BatteryControllerImpl extends BroadcastReceiver implements BatteryController
{
    private static final boolean DEBUG;
    protected boolean mAodPowerSave;
    private int mBatteryStyle;
    private final ArrayList<BatteryController$BatteryStateChangeCallback> mChangeCallbacks;
    protected boolean mCharged;
    protected boolean mCharging;
    private final Context mContext;
    private boolean mDemoMode;
    private Estimate mEstimate;
    private final EnhancedEstimates mEstimates;
    private int mFastchargeType;
    private final ArrayList<BatteryController$EstimateFetchCompletion> mFetchCallbacks;
    private boolean mFetchingEstimate;
    private final Handler mHandler;
    private boolean mHasReceivedBattery;
    private boolean mIsOptimizatedCharge;
    protected int mLevel;
    protected boolean mPluggedIn;
    private final PowerManager mPowerManager;
    protected boolean mPowerSave;
    private final BatteryControllerImpl.SettingObserver mSettingObserver;
    private boolean mShowPercent;
    private boolean mTestmode;
    
    static {
        DEBUG = Log.isLoggable("BatteryController", 3);
    }
    
    public BatteryControllerImpl(final Context context, final EnhancedEstimates enhancedEstimates) {
        this(context, enhancedEstimates, (PowerManager)context.getSystemService((Class)PowerManager.class));
    }
    
    @VisibleForTesting
    BatteryControllerImpl(final Context mContext, final EnhancedEstimates mEstimates, final PowerManager mPowerManager) {
        this.mChangeCallbacks = new ArrayList<BatteryController$BatteryStateChangeCallback>();
        this.mFetchCallbacks = new ArrayList<BatteryController$EstimateFetchCompletion>();
        this.mTestmode = false;
        this.mHasReceivedBattery = false;
        this.mFetchingEstimate = false;
        this.mFastchargeType = 0;
        this.mShowPercent = false;
        this.mSettingObserver = new BatteryControllerImpl.SettingObserver(this);
        this.mBatteryStyle = 0;
        this.mIsOptimizatedCharge = false;
        this.mContext = mContext;
        this.mHandler = new Handler();
        this.mPowerManager = mPowerManager;
        this.mEstimates = mEstimates;
        this.registerReceiver();
        this.updatePowerSave();
        this.updateEstimate();
    }
    
    private void fireBatteryStylechange() {
        synchronized (this.mChangeCallbacks) {
            final int size = this.mChangeCallbacks.size();
            final StringBuilder sb = new StringBuilder();
            sb.append(" fireBatteryStylechange mShowPercent:");
            sb.append(this.mShowPercent);
            sb.append(" mBatteryStyle:");
            sb.append(this.mBatteryStyle);
            sb.append(" mFastchargeType:");
            sb.append(this.mFastchargeType);
            Log.i("BatteryController", sb.toString());
            for (int i = 0; i < size; ++i) {
                try {
                    this.mChangeCallbacks.get(i).onBatteryPercentShowChange(this.mShowPercent);
                    this.mChangeCallbacks.get(i).onBatteryStyleChanged(this.mBatteryStyle);
                }
                catch (IndexOutOfBoundsException ex) {
                    final StringBuilder sb2 = new StringBuilder();
                    sb2.append(" fireBatteryStylechange:");
                    sb2.append(ex.getMessage());
                    Log.i("BatteryController", sb2.toString());
                }
            }
        }
    }
    
    private void fireOptimizatedStatusChange() {
        synchronized (this.mChangeCallbacks) {
            final int size = this.mChangeCallbacks.size();
            final StringBuilder sb = new StringBuilder();
            sb.append(" fireOptimizatedStatusChange mIsOptimizatedCharge:");
            sb.append(this.mIsOptimizatedCharge);
            Log.i("BatteryController", sb.toString());
            for (int i = 0; i < size; ++i) {
                try {
                    this.mChangeCallbacks.get(i).onOptimizatedStatusChange(this.mIsOptimizatedCharge);
                }
                catch (IndexOutOfBoundsException ex) {
                    final StringBuilder sb2 = new StringBuilder();
                    sb2.append(" fireOptimizatedStatusChange:");
                    sb2.append(ex.getMessage());
                    Log.i("BatteryController", sb2.toString());
                }
            }
        }
    }
    
    private void firePowerSaveChanged() {
        final StringBuilder sb = new StringBuilder();
        sb.append(" firePowerSaveChanged mPowerSave:");
        sb.append(this.mPowerSave);
        Log.i("BatteryController", sb.toString());
        synchronized (this.mChangeCallbacks) {
            for (int size = this.mChangeCallbacks.size(), i = 0; i < size; ++i) {
                try {
                    this.mChangeCallbacks.get(i).onPowerSaveChanged(this.mPowerSave);
                }
                catch (IndexOutOfBoundsException ex) {
                    final StringBuilder sb2 = new StringBuilder();
                    sb2.append("firePowerSaveChanged:");
                    sb2.append(ex.getMessage());
                    Log.i("BatteryController", sb2.toString());
                }
            }
        }
    }
    
    private String generateTimeRemainingString() {
        synchronized (this.mFetchCallbacks) {
            if (this.mEstimate == null) {
                return null;
            }
            NumberFormat.getPercentInstance().format(this.mLevel / 100.0);
            return PowerUtil.getBatteryRemainingShortStringFormatted(this.mContext, this.mEstimate.getEstimateMillis());
        }
    }
    
    private void notifyEstimateFetchCallbacks() {
        synchronized (this.mFetchCallbacks) {
            final String generateTimeRemainingString = this.generateTimeRemainingString();
            final Iterator<BatteryController$EstimateFetchCompletion> iterator = this.mFetchCallbacks.iterator();
            while (iterator.hasNext()) {
                iterator.next().onBatteryRemainingEstimateRetrieved(generateTimeRemainingString);
            }
            this.mFetchCallbacks.clear();
        }
    }
    
    private void registerReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.BATTERY_CHANGED");
        intentFilter.addAction("android.os.action.POWER_SAVE_MODE_CHANGED");
        intentFilter.addAction("android.os.action.POWER_SAVE_MODE_CHANGING");
        intentFilter.addAction("com.android.systemui.BATTERY_LEVEL_TEST");
        intentFilter.addAction("android.intent.action.BOOT_COMPLETED");
        this.mContext.registerReceiver((BroadcastReceiver)this, intentFilter);
        this.mSettingObserver.observe();
    }
    
    private void setPowerSave(final boolean mPowerSave) {
        if (mPowerSave == this.mPowerSave) {
            return;
        }
        this.mPowerSave = mPowerSave;
        this.mAodPowerSave = this.mPowerManager.getPowerSaveState(14).batterySaverEnabled;
        if (BatteryControllerImpl.DEBUG) {
            final StringBuilder sb = new StringBuilder();
            sb.append("Power save is ");
            String str;
            if (this.mPowerSave) {
                str = "on";
            }
            else {
                str = "off";
            }
            sb.append(str);
            Log.d("BatteryController", sb.toString());
        }
        this.firePowerSaveChanged();
    }
    
    private void updateEstimate() {
        this.mEstimate = Estimate.getCachedEstimateIfAvailable(this.mContext);
        if (this.mEstimate == null) {
            this.mEstimate = this.mEstimates.getEstimate();
            final Estimate mEstimate = this.mEstimate;
            if (mEstimate != null) {
                Estimate.storeCachedEstimate(this.mContext, mEstimate);
            }
        }
    }
    
    private void updateEstimateInBackground() {
        if (this.mFetchingEstimate) {
            return;
        }
        this.mFetchingEstimate = true;
        ((Handler)Dependency.get(Dependency.BG_HANDLER)).post((Runnable)new _$$Lambda$BatteryControllerImpl$Q2m5_jQFbUIrN5_x5MkihyCoos8(this));
    }
    
    private void updatePowerSave() {
        this.setPowerSave(this.mPowerManager.isPowerSaveMode());
    }
    
    public void addCallback(final BatteryController$BatteryStateChangeCallback e) {
        synchronized (this.mChangeCallbacks) {
            this.mChangeCallbacks.add(e);
            // monitorexit(this.mChangeCallbacks)
            e.onOptimizatedStatusChange(this.mIsOptimizatedCharge);
            if (!this.mHasReceivedBattery) {
                return;
            }
            e.onBatteryLevelChanged(this.mLevel, this.mPluggedIn, this.mCharging);
            e.onPowerSaveChanged(this.mPowerSave);
            e.onFastChargeChanged(this.mFastchargeType);
            e.onBatteryStyleChanged(this.mBatteryStyle);
            e.onBatteryPercentShowChange(this.mShowPercent);
        }
    }
    
    public void dispatchDemoCommand(String string, final Bundle bundle) {
        if (!this.mDemoMode && string.equals("enter")) {
            this.mDemoMode = true;
            this.mContext.unregisterReceiver((BroadcastReceiver)this);
        }
        else if (this.mDemoMode && string.equals("exit")) {
            this.mDemoMode = false;
            this.registerReceiver();
            this.updatePowerSave();
        }
        else if (this.mDemoMode && string.equals("battery")) {
            final String string2 = bundle.getString("level");
            final String string3 = bundle.getString("plugged");
            string = bundle.getString("powersave");
            final String string4 = bundle.getString("powerOptimizated");
            if (string2 != null) {
                this.mLevel = Math.min(Math.max(Integer.parseInt(string2), 0), 100);
            }
            if (string3 != null) {
                this.mPluggedIn = Boolean.parseBoolean(string3);
            }
            if (string != null) {
                this.mPowerSave = string.equals("true");
                this.firePowerSaveChanged();
            }
            if (string4 != null) {
                this.mIsOptimizatedCharge = string4.equals("true");
                this.fireOptimizatedStatusChange();
            }
            this.fireBatteryLevelChanged();
        }
    }
    
    public void dump(final FileDescriptor fileDescriptor, final PrintWriter printWriter, final String[] array) {
        printWriter.println("BatteryController state:");
        printWriter.print("  mLevel=");
        printWriter.println(this.mLevel);
        printWriter.print("  mPluggedIn=");
        printWriter.println(this.mPluggedIn);
        printWriter.print("  mCharging=");
        printWriter.println(this.mCharging);
        printWriter.print("  mCharged=");
        printWriter.println(this.mCharged);
        printWriter.print("  mPowerSave=");
        printWriter.println(this.mPowerSave);
        printWriter.print("  mShowPercent=");
        printWriter.println(this.mShowPercent);
        printWriter.print("  mBatteryStyle=");
        printWriter.println(this.mBatteryStyle);
    }
    
    protected void fireBatteryLevelChanged() {
        if (Build.DEBUG_ONEPLUS) {
            final StringBuilder sb = new StringBuilder();
            sb.append(" fireBatteryLevelChanged mLevel:");
            sb.append(this.mLevel);
            sb.append(" PluggedIn:");
            sb.append(this.mPluggedIn);
            sb.append(" Charging:");
            sb.append(this.mCharging);
            sb.append(" mFastchargeType:");
            sb.append(this.mFastchargeType);
            sb.append(" show:");
            sb.append(this.mShowPercent);
            sb.append(" style:");
            sb.append(this.mBatteryStyle);
            Log.i("BatteryController", sb.toString());
        }
        synchronized (this.mChangeCallbacks) {
            for (int size = this.mChangeCallbacks.size(), i = 0; i < size; ++i) {
                this.mChangeCallbacks.get(i).onBatteryLevelChanged(this.mLevel, this.mPluggedIn, this.mCharging);
                this.mChangeCallbacks.get(i).onFastChargeChanged(this.mFastchargeType);
            }
        }
    }
    
    public void getEstimatedTimeRemainingString(final BatteryController$EstimateFetchCompletion e) {
        synchronized (this.mFetchCallbacks) {
            this.mFetchCallbacks.add(e);
            // monitorexit(this.mFetchCallbacks)
            this.updateEstimateInBackground();
        }
    }
    
    public boolean isAodPowerSave() {
        return this.mAodPowerSave;
    }
    
    public boolean isFastCharging(final int n) {
        return n == 1;
    }
    
    public boolean isPowerSave() {
        return this.mPowerSave;
    }
    
    public boolean isWarpCharging(final int n) {
        return n == 2 || n == 3;
    }
    
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();
        if (action.equals("android.intent.action.BATTERY_CHANGED")) {
            if (this.mTestmode && !intent.getBooleanExtra("testmode", false)) {
                return;
            }
            final boolean mHasReceivedBattery = this.mHasReceivedBattery;
            this.mHasReceivedBattery = true;
            this.mLevel = (int)(intent.getIntExtra("level", 0) * 100.0f / intent.getIntExtra("scale", 100));
            this.mPluggedIn = (intent.getIntExtra("plugged", 0) != 0);
            final int intExtra = intent.getIntExtra("status", 1);
            this.mCharged = (intExtra == 5);
            this.mCharging = (this.mCharged || intExtra == 2);
            int intExtra2 = intent.getIntExtra("fastcharge_status", 0);
            if (!OpUtils.SUPPORT_WARP_CHARGING) {
                if (intExtra2 > 0) {
                    intExtra2 = 1;
                }
                else {
                    intExtra2 = 0;
                }
            }
            this.mFastchargeType = intExtra2;
            if (mHasReceivedBattery ^ true) {
                this.fireBatteryStylechange();
            }
            this.fireBatteryLevelChanged();
        }
        else if (action.equals("android.os.action.POWER_SAVE_MODE_CHANGED")) {
            this.updatePowerSave();
        }
        else if (action.equals("android.os.action.POWER_SAVE_MODE_CHANGING")) {
            this.setPowerSave(intent.getBooleanExtra("mode", false));
        }
        else if (action.equals("android.intent.action.BOOT_COMPLETED")) {
            this.mSettingObserver.update((Uri)null);
        }
        else if (action.equals("com.android.systemui.BATTERY_LEVEL_TEST")) {
            this.mTestmode = true;
            this.mHandler.post((Runnable)new BatteryControllerImpl.BatteryControllerImpl$1(this, context));
        }
    }
    
    public void removeCallback(final BatteryController$BatteryStateChangeCallback o) {
        synchronized (this.mChangeCallbacks) {
            this.mChangeCallbacks.remove(o);
        }
    }
    
    public void setPowerSaveMode(final boolean b) {
        BatterySaverUtils.setPowerSaveMode(this.mContext, b, true);
    }
}