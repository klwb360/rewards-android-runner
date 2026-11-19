package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;

/** The native display reader accepts a card crop from an existing live card or the bound news port only. */
abstract sealed class OwnedCardCropPermit permits LiveRunAuthority.CropPermit, NewsAndroidPort.Crop {
    abstract boolean live();
    abstract boolean current(long now);
    abstract long deadline();
    abstract boolean afterWindow();
    abstract boolean activeForRetirement(long now);
    abstract boolean bindRequest(Box roi, int width, int height, int rotation, long now);
    abstract boolean claimDispatch(long now);
}
