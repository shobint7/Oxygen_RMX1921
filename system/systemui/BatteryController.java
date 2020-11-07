package com.android.systemui.statusbar.policy;

import com.android.systemui.Dumpable;
import com.android.systemui.DemoMode;

public interface BatteryController extends DemoMode, Dumpable, CallbackController<BatteryController.BatteryStateChangeCallback>
{
    default void getEstimatedTimeRemainingString(final BatteryController.EstimateFetchCompletion estimateFetchCompletion) {
    }
    
    default boolean isAodPowerSave() {
        return this.isPowerSave();
    }
    
    default boolean isFastCharging(final int n) {
        return false;
    }
    
    boolean isPowerSave();
    
    default boolean isWarpCharging(final int n) {
        return true;
    }
    
    void setPowerSaveMode(final boolean p0);
}