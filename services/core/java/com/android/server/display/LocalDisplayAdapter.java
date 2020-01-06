/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.sidekick.SidekickInternal;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.server.LocalServices;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A display adapter for the local displays managed by Surface Flinger.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class LocalDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "LocalDisplayAdapter";
    private static final boolean DEBUG = false;

    private static final String UNIQUE_ID_PREFIX = "local:";

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";

    private final LongSparseArray<LocalDisplayDevice> mDevices =
            new LongSparseArray<LocalDisplayDevice>();

    @SuppressWarnings("unused")  // Becomes active at instantiation time.
    private PhysicalDisplayEventReceiver mPhysicalDisplayEventReceiver;


    // Called with SyncRoot lock held.
    public LocalDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
    }

    @Override
    public void registerLocked() {
        super.registerLocked();

        mPhysicalDisplayEventReceiver = new PhysicalDisplayEventReceiver(getHandler().getLooper());

        for (long physicalDisplayId : SurfaceControl.getPhysicalDisplayIds()) {
            tryConnectDisplayLocked(physicalDisplayId);
        }
    }

    private void tryConnectDisplayLocked(long physicalDisplayId) {
        final IBinder displayToken = SurfaceControl.getPhysicalDisplayToken(physicalDisplayId);
        if (displayToken != null) {
            SurfaceControl.PhysicalDisplayInfo[] configs =
                    SurfaceControl.getDisplayConfigs(displayToken);
            if (configs == null) {
                // There are no valid configs for this device, so we can't use it
                Slog.w(TAG, "No valid configs found for display device " + physicalDisplayId);
                return;
            }
            int activeConfig = SurfaceControl.getActiveConfig(displayToken);
            if (activeConfig < 0) {
                // There is no active config, and for now we don't have the
                // policy to set one.
                Slog.w(TAG, "No active config found for display device " + physicalDisplayId);
                return;
            }
            int activeColorMode = SurfaceControl.getActiveColorMode(displayToken);
            if (activeColorMode < 0) {
                // We failed to get the active color mode. We don't bail out here since on the next
                // configuration pass we'll go ahead and set it to whatever it was set to last (or
                // COLOR_MODE_NATIVE if this is the first configuration).
                Slog.w(TAG, "Unable to get active color mode for display device " +
                        physicalDisplayId);
                activeColorMode = Display.COLOR_MODE_INVALID;
            }
            int[] colorModes = SurfaceControl.getDisplayColorModes(displayToken);
            int[] allowedConfigs = SurfaceControl.getAllowedDisplayConfigs(displayToken);
            LocalDisplayDevice device = mDevices.get(physicalDisplayId);
            if (device == null) {
                // Display was added.
                final boolean isInternal = mDevices.size() == 0;
                device = new LocalDisplayDevice(displayToken, physicalDisplayId,
                        configs, activeConfig, allowedConfigs, colorModes, activeColorMode,
                        isInternal);
                mDevices.put(physicalDisplayId, device);
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
            } else if (device.updatePhysicalDisplayInfoLocked(configs, activeConfig,
                        allowedConfigs, colorModes, activeColorMode)) {
                // Display properties changed.
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_CHANGED);
            }
        } else {
            // The display is no longer available. Ignore the attempt to add it.
            // If it was connected but has already been disconnected, we'll get a
            // disconnect event that will remove it from mDevices.
        }
    }

    private void tryDisconnectDisplayLocked(long physicalDisplayId) {
        LocalDisplayDevice device = mDevices.get(physicalDisplayId);
        if (device != null) {
            // Display was removed.
            mDevices.remove(physicalDisplayId);
            sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
        }
    }

    static int getPowerModeForState(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return SurfaceControl.POWER_MODE_OFF;
            case Display.STATE_DOZE:
                return SurfaceControl.POWER_MODE_DOZE;
            case Display.STATE_DOZE_SUSPEND:
                return SurfaceControl.POWER_MODE_DOZE_SUSPEND;
            case Display.STATE_ON_SUSPEND:
                return SurfaceControl.POWER_MODE_ON_SUSPEND;
            default:
                return SurfaceControl.POWER_MODE_NORMAL;
        }
    }

    private final class LocalDisplayDevice extends DisplayDevice {
        private final long mPhysicalDisplayId;
        private final Light mBacklight;
        private final SparseArray<DisplayModeRecord> mSupportedModes = new SparseArray<>();
        private final ArrayList<Integer> mSupportedColorModes = new ArrayList<>();
        private final boolean mIsInternal;

        private DisplayDeviceInfo mInfo;
        private boolean mHavePendingChanges;
        private int mState = Display.STATE_UNKNOWN;
        private int mBrightness = PowerManager.BRIGHTNESS_DEFAULT;
        private int mDefaultModeId;
        private int mActiveModeId;
        private boolean mActiveModeInvalid;
        private int[] mAllowedModeIds;
        private float mMinRefreshRate;
        private float mMaxRefreshRate;
        private boolean mAllowedModeIdsInvalid;
        private int mActivePhysIndex;
        private int[] mAllowedPhysIndexes;
        private int mActiveColorMode;
        private boolean mActiveColorModeInvalid;
        private Display.HdrCapabilities mHdrCapabilities;
        private boolean mSidekickActive;
        private SidekickInternal mSidekickInternal;

        private SurfaceControl.PhysicalDisplayInfo[] mDisplayInfos;

        LocalDisplayDevice(IBinder displayToken, long physicalDisplayId,
                SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo,
                int[] allowedDisplayInfos, int[] colorModes, int activeColorMode,
                boolean isInternal) {
            super(LocalDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + physicalDisplayId);
            mPhysicalDisplayId = physicalDisplayId;
            mIsInternal = isInternal;
            updatePhysicalDisplayInfoLocked(physicalDisplayInfos, activeDisplayInfo,
                    allowedDisplayInfos, colorModes, activeColorMode);
            updateColorModesLocked(colorModes, activeColorMode);
            mSidekickInternal = LocalServices.getService(SidekickInternal.class);
            if (mIsInternal) {
                LightsManager lights = LocalServices.getService(LightsManager.class);
                mBacklight = lights.getLight(LightsManager.LIGHT_ID_BACKLIGHT);
            } else {
                mBacklight = null;
            }
            mHdrCapabilities = SurfaceControl.getHdrCapabilities(displayToken);
        }

        @Override
        public boolean hasStableUniqueId() {
            return true;
        }

        public boolean updatePhysicalDisplayInfoLocked(
                SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo,
                int[] allowedDisplayInfos, int[] colorModes, int activeColorMode) {
            mDisplayInfos = Arrays.copyOf(physicalDisplayInfos, physicalDisplayInfos.length);
            mActivePhysIndex = activeDisplayInfo;
            mAllowedPhysIndexes = Arrays.copyOf(allowedDisplayInfos, allowedDisplayInfos.length);
            // Build an updated list of all existing modes.
            ArrayList<DisplayModeRecord> records = new ArrayList<DisplayModeRecord>();
            boolean modesAdded = false;
            for (int i = 0; i < physicalDisplayInfos.length; i++) {
                SurfaceControl.PhysicalDisplayInfo info = physicalDisplayInfos[i];
                // First, check to see if we've already added a matching mode. Since not all
                // configuration options are exposed via Display.Mode, it's possible that we have
                // multiple PhysicalDisplayInfos that would generate the same Display.Mode.
                boolean existingMode = false;
                for (int j = 0; j < records.size(); j++) {
                    if (records.get(j).hasMatchingMode(info)) {
                        existingMode = true;
                        break;
                    }
                }
                if (existingMode) {
                    continue;
                }
                // If we haven't already added a mode for this configuration to the new set of
                // supported modes then check to see if we have one in the prior set of supported
                // modes to reuse.
                DisplayModeRecord record = findDisplayModeRecord(info);
                if (record == null) {
                    record = new DisplayModeRecord(info);
                    modesAdded = true;
                }
                records.add(record);
            }

            // Get the currently active mode
            DisplayModeRecord activeRecord = null;
            for (int i = 0; i < records.size(); i++) {
                DisplayModeRecord record = records.get(i);
                if (record.hasMatchingMode(physicalDisplayInfos[activeDisplayInfo])){
                    activeRecord = record;
                    break;
                }
            }

            // Check whether surface flinger spontaneously changed modes out from under us.
            // Schedule traversals to ensure that the correct state is reapplied if necessary.
            if (mActiveModeId != 0
                    && mActiveModeId != activeRecord.mMode.getModeId()) {
                mActiveModeInvalid = true;
                sendTraversalRequestLocked();
            }

            boolean recordsChanged = records.size() != mSupportedModes.size() || modesAdded;
            // If the records haven't changed then we're done here.
            if (!recordsChanged) {
                return false;
            }
            // Update the index of modes.
            mHavePendingChanges = true;

            mSupportedModes.clear();
            for (DisplayModeRecord record : records) {
                mSupportedModes.put(record.mMode.getModeId(), record);
            }

            // Update the default mode, if needed.
            if (findDisplayInfoIndexLocked(mDefaultModeId) < 0) {
                if (mDefaultModeId != 0) {
                    Slog.w(TAG, "Default display mode no longer available, using currently"
                            + " active mode as default.");
                }
                mDefaultModeId = activeRecord.mMode.getModeId();
            }

            // Determine whether the active mode is still there.
            if (mSupportedModes.indexOfKey(mActiveModeId) < 0) {
                if (mActiveModeId != 0) {
                    Slog.w(TAG, "Active display mode no longer available, reverting to default"
                            + " mode.");
                }
                mActiveModeId = mDefaultModeId;
                mActiveModeInvalid = true;
            }

            // Determine what the currently allowed modes are
            mAllowedModeIds = new int[] { mActiveModeId };
            int[] allowedModeIds = new int[mAllowedPhysIndexes.length];
            int size = 0;
            for (int physIndex : mAllowedPhysIndexes) {
                int modeId = findMatchingModeIdLocked(physIndex);
                if (modeId > 0) {
                    allowedModeIds[size++] = modeId;
                }
            }

            // If this is different from our desired allowed modes, then mark our current set as
            // invalid so we correct this on the next traversal.
            mAllowedModeIdsInvalid = !Arrays.equals(allowedModeIds, mAllowedModeIds);

            // Schedule traversals so that we apply pending changes.
            sendTraversalRequestLocked();
            return true;
        }

        private boolean updateColorModesLocked(int[] colorModes,
                int activeColorMode) {
            List<Integer> pendingColorModes = new ArrayList<>();

            if (colorModes == null) return false;
            // Build an updated list of all existing color modes.
            boolean colorModesAdded = false;
            for (int colorMode: colorModes) {
                if (!mSupportedColorModes.contains(colorMode)) {
                    colorModesAdded = true;
                }
                pendingColorModes.add(colorMode);
            }

            boolean colorModesChanged =
                    pendingColorModes.size() != mSupportedColorModes.size()
                    || colorModesAdded;

            // If the supported color modes haven't changed then we're done here.
            if (!colorModesChanged) {
                return false;
            }

            mHavePendingChanges = true;

            mSupportedColorModes.clear();
            mSupportedColorModes.addAll(pendingColorModes);
            Collections.sort(mSupportedColorModes);

            // Determine whether the active color mode is still there.
            if (!mSupportedColorModes.contains(mActiveColorMode)) {
                if (mActiveColorMode != 0) {
                    Slog.w(TAG, "Active color mode no longer available, reverting"
                            + " to default mode.");
                    mActiveColorMode = Display.COLOR_MODE_DEFAULT;
                    mActiveColorModeInvalid = true;
                } else {
                    if (!mSupportedColorModes.isEmpty()) {
                        // This should never happen.
                        Slog.e(TAG, "Default and active color mode is no longer available!"
                                + " Reverting to first available mode.");
                        mActiveColorMode = mSupportedColorModes.get(0);
                        mActiveColorModeInvalid = true;
                    } else {
                        // This should really never happen.
                        Slog.e(TAG, "No color modes available!");
                    }
                }
            }
            return true;
        }

        private DisplayModeRecord findDisplayModeRecord(SurfaceControl.PhysicalDisplayInfo info) {
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(info)) {
                    return record;
                }
            }
            return null;
        }

        @Override
        public void applyPendingDisplayDeviceInfoChangesLocked() {
            if (mHavePendingChanges) {
                mInfo = null;
                mHavePendingChanges = false;
            }
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                SurfaceControl.PhysicalDisplayInfo phys = mDisplayInfos[mActivePhysIndex];
                mInfo = new DisplayDeviceInfo();
                mInfo.width = phys.width;
                mInfo.height = phys.height;
                mInfo.modeId = mActiveModeId;
                mInfo.defaultModeId = mDefaultModeId;
                mInfo.supportedModes = getDisplayModes(mSupportedModes);
                mInfo.colorMode = mActiveColorMode;
                mInfo.supportedColorModes =
                        new int[mSupportedColorModes.size()];
                for (int i = 0; i < mSupportedColorModes.size(); i++) {
                    mInfo.supportedColorModes[i] = mSupportedColorModes.get(i);
                }
                mInfo.hdrCapabilities = mHdrCapabilities;
                mInfo.appVsyncOffsetNanos = phys.appVsyncOffsetNanos;
                mInfo.presentationDeadlineNanos = phys.presentationDeadlineNanos;
                mInfo.state = mState;
                mInfo.uniqueId = getUniqueId();
                final DisplayAddress.Physical physicalAddress =
                        DisplayAddress.fromPhysicalDisplayId(mPhysicalDisplayId);
                mInfo.address = physicalAddress;
                mInfo.densityDpi = (int) (phys.density * 160 + 0.5f);
                mInfo.xDpi = phys.xDpi;
                mInfo.yDpi = phys.yDpi;

                // Assume that all built-in displays that have secure output (eg. HDCP) also
                // support compositing from gralloc protected buffers.
                if (phys.secure) {
                    mInfo.flags = DisplayDeviceInfo.FLAG_SECURE
                            | DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS;
                }

                final Resources res = getOverlayContext().getResources();
                final boolean isBuiltIn = ((mInfo.address) != null) ?
                   (((DisplayAddress.Physical) mInfo.address).getPort() < 0) : false;
                if (mIsInternal) {
                    mInfo.name = res.getString(
                            com.android.internal.R.string.display_manager_built_in_display_name);
                    mInfo.flags |= DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY
                            | DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                    if (res.getBoolean(com.android.internal.R.bool.config_mainBuiltInDisplayIsRound)
                            || (Build.IS_EMULATOR
                            && SystemProperties.getBoolean(PROPERTY_EMULATOR_CIRCULAR, false))) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_ROUND;
                    }
                    if (res.getBoolean(
                            com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_MASK_DISPLAY_CUTOUT;
                    }
                    mInfo.displayCutout = DisplayCutout.fromResourcesRectApproximation(res,
                            mInfo.width, mInfo.height);
                    mInfo.type = Display.TYPE_BUILT_IN;
                    mInfo.touch = DisplayDeviceInfo.TOUCH_INTERNAL;
                } else if (isBuiltIn) {
                    mInfo.type = Display.TYPE_BUILT_IN;
                    mInfo.touch = DisplayDeviceInfo.TOUCH_INTERNAL;
                    mInfo.name = getContext().getResources().getString(
                            com.android.internal.R.string.display_manager_built_in_display_name);
                    mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;

                    if (SystemProperties.getBoolean(
                                    "vendor.display.builtin_presentation", false)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;
                    } else {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE;
                    }

                    if (!SystemProperties.getBoolean(
                                    "vendor.display.builtin_mirroring", false)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                    }

                    mInfo.setAssumedDensityForExternalDisplay(phys.width, phys.height);
                } else {
                    mInfo.displayCutout = null;
                    mInfo.type = Display.TYPE_HDMI;
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;
                    mInfo.name = getContext().getResources().getString(
                            com.android.internal.R.string.display_manager_hdmi_display_name);
                    mInfo.touch = DisplayDeviceInfo.TOUCH_EXTERNAL;

                    // For demonstration purposes, allow rotation of the external display.
                    // In the future we might allow the user to configure this directly.
                    if ("portrait".equals(SystemProperties.get("persist.demo.hdmirotation"))) {
                        mInfo.rotation = Surface.ROTATION_270;
                    }

                    // For demonstration purposes, allow rotation of the external display
                    // to follow the built-in display.
                    if (SystemProperties.getBoolean("persist.demo.hdmirotates", false)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                    }

                    if (!res.getBoolean(
                                com.android.internal.R.bool.config_localDisplaysMirrorContent)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                    }

                    if (isDisplayPrivate(physicalAddress)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE;
                    }
                }
            }
            return mInfo;
        }

        @Override
        public Runnable requestDisplayStateLocked(final int state, final int brightness) {
            // Assume that the brightness is off if the display is being turned off.
            assert state != Display.STATE_OFF || brightness == PowerManager.BRIGHTNESS_OFF;

            final boolean stateChanged = (mState != state);
            final boolean brightnessChanged = (mBrightness != brightness) && mBacklight != null;
            if (stateChanged || brightnessChanged) {
                final long physicalDisplayId = mPhysicalDisplayId;
                final IBinder token = getDisplayTokenLocked();
                final int oldState = mState;

                if (stateChanged) {
                    mState = state;
                    updateDeviceInfoLocked();
                }

                if (brightnessChanged) {
                    mBrightness = brightness;
                }

                // Defer actually setting the display state until after we have exited
                // the critical section since it can take hundreds of milliseconds
                // to complete.
                return new Runnable() {
                    @Override
                    public void run() {
                        // Exit a suspended state before making any changes.
                        int currentState = oldState;
                        if (Display.isSuspendedState(oldState)
                                || oldState == Display.STATE_UNKNOWN) {
                            if (!Display.isSuspendedState(state)) {
                                setDisplayState(state);
                                currentState = state;
                            } else if (state == Display.STATE_DOZE_SUSPEND
                                    || oldState == Display.STATE_DOZE_SUSPEND) {
                                setDisplayState(Display.STATE_DOZE);
                                currentState = Display.STATE_DOZE;
                            } else if (state == Display.STATE_ON_SUSPEND
                                    || oldState == Display.STATE_ON_SUSPEND) {
                                setDisplayState(Display.STATE_ON);
                                currentState = Display.STATE_ON;
                            } else {
                                return; // old state and new state is off
                            }
                        }

                        // If the state change was from or to VR, then we need to tell the light
                        // so that it can apply appropriate VR brightness settings. Also, update the
                        // brightness so the state is propogated to light.
                        boolean vrModeChange = false;
                        if ((state == Display.STATE_VR || currentState == Display.STATE_VR) &&
                                currentState != state) {
                            setVrMode(state == Display.STATE_VR);
                            vrModeChange = true;
                        }


                        // Apply brightness changes given that we are in a non-suspended state.
                        if (brightnessChanged || vrModeChange) {
                            setDisplayBrightness(brightness);
                        }

                        // Enter the final desired state, possibly suspended.
                        if (state != currentState) {
                            setDisplayState(state);
                        }
                    }

                    private void setVrMode(boolean isVrEnabled) {
                        if (DEBUG) {
                            Slog.d(TAG, "setVrMode("
                                    + "id=" + physicalDisplayId
                                    + ", state=" + Display.stateToString(state) + ")");
                        }
                        mBacklight.setVrMode(isVrEnabled);
                    }

                    private void setDisplayState(int state) {
                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayState("
                                    + "id=" + physicalDisplayId
                                    + ", state=" + Display.stateToString(state) + ")");
                        }

                        // We must tell sidekick to stop controlling the display before we
                        // can change its power mode, so do that first.
                        if (mSidekickActive) {
                            Trace.traceBegin(Trace.TRACE_TAG_POWER,
                                    "SidekickInternal#endDisplayControl");
                            try {
                                mSidekickInternal.endDisplayControl();
                            } finally {
                                Trace.traceEnd(Trace.TRACE_TAG_POWER);
                            }
                            mSidekickActive = false;
                        }
                        final int mode = getPowerModeForState(state);
                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayState("
                                + "id=" + physicalDisplayId
                                + ", state=" + Display.stateToString(state) + ")");
                        try {
                            SurfaceControl.setDisplayPowerMode(token, mode);
                            Trace.traceCounter(Trace.TRACE_TAG_POWER, "DisplayPowerMode", mode);
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                        // If we're entering a suspended (but not OFF) power state and we
                        // have a sidekick available, tell it now that it can take control.
                        if (Display.isSuspendedState(state) && state != Display.STATE_OFF
                                && mSidekickInternal != null && !mSidekickActive) {
                            Trace.traceBegin(Trace.TRACE_TAG_POWER,
                                    "SidekickInternal#startDisplayControl");
                            try {
                                mSidekickActive = mSidekickInternal.startDisplayControl(state);
                            } finally {
                                Trace.traceEnd(Trace.TRACE_TAG_POWER);
                            }
                        }
                    }

                    private void setDisplayBrightness(int brightness) {
                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayBrightness("
                                    + "id=" + physicalDisplayId
                                    + ", brightness=" + brightness + ")");
                        }

                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayBrightness("
                                + "id=" + physicalDisplayId + ", brightness=" + brightness + ")");
                        try {
                            mBacklight.setBrightness(brightness);
                            Trace.traceCounter(Trace.TRACE_TAG_POWER,
                                    "ScreenBrightness", brightness);
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                    }
                };
            }
            return null;
        }

        @Override
        public void setRequestedColorModeLocked(int colorMode) {
            if (requestColorModeLocked(colorMode)) {
                updateDeviceInfoLocked();
            }
        }

        @Override
        public void setDesiredDisplayConfigSpecs(int defaultModeId, float minRefreshRate,
                float maxRefreshRate, int[] modes) {
            updateDesiredDisplayConfigSpecs(defaultModeId, minRefreshRate, maxRefreshRate);
            updateAllowedModesLocked(modes);
        }

        @Override
        public void onOverlayChangedLocked() {
            updateDeviceInfoLocked();
        }

        public void onActivePhysicalDisplayModeChangedLocked(int physIndex) {
            if (updateActiveModeLocked(physIndex)) {
                updateDeviceInfoLocked();
            }
        }

        public boolean updateActiveModeLocked(int activePhysIndex) {
            if (mActivePhysIndex == activePhysIndex) {
                return false;
            }
            mActivePhysIndex = activePhysIndex;
            mActiveModeId = findMatchingModeIdLocked(activePhysIndex);
            mActiveModeInvalid = mActiveModeId == 0;
            if (mActiveModeInvalid) {
                Slog.w(TAG, "In unknown mode after setting allowed configs"
                        + ": allowedPhysIndexes=" + mAllowedPhysIndexes
                        + ", activePhysIndex=" + mActivePhysIndex);
            }
            return true;
        }

        // TODO(b/142507213): Remove once refresh rates are plummed through to kernel.
        public void updateAllowedModesLocked(int[] allowedModes) {
            if (Arrays.equals(allowedModes, mAllowedModeIds) && !mAllowedModeIdsInvalid) {
                return;
            }
            if (updateAllowedModesInternalLocked(allowedModes)) {
                updateDeviceInfoLocked();
            }
        }

        public void updateDesiredDisplayConfigSpecs(int defaultModeId, float minRefreshRate,
                float maxRefreshRate) {
            if (minRefreshRate == mMinRefreshRate
                        && maxRefreshRate == mMaxRefreshRate
                        && defaultModeId == mDefaultModeId) {
                return;
            }
            if (updateDesiredDisplayConfigSpecsInternalLocked(defaultModeId, minRefreshRate,
                    maxRefreshRate)) {
                updateDeviceInfoLocked();
            }
        }

        public boolean updateDesiredDisplayConfigSpecsInternalLocked(int defaultModeId,
                float minRefreshRate, float maxRefreshRate) {
            if (DEBUG) {
                Slog.w(TAG, "updateDesiredDisplayConfigSpecsInternalLocked("
                        + "defaultModeId="
                        + Integer.toString(defaultModeId)
                        + ", minRefreshRate="
                        + Float.toString(minRefreshRate)
                        + ", maxRefreshRate="
                        + Float.toString(minRefreshRate));
            }

            final IBinder token = getDisplayTokenLocked();
            SurfaceControl.setDesiredDisplayConfigSpecs(token,
                    new SurfaceControl.DesiredDisplayConfigSpecs(
                            defaultModeId, minRefreshRate, maxRefreshRate));
            int activePhysIndex = SurfaceControl.getActiveConfig(token);
            return updateActiveModeLocked(activePhysIndex);
        }

        public boolean updateAllowedModesInternalLocked(int[] allowedModes) {
            if (DEBUG) {
                Slog.w(TAG, "updateAllowedModesInternalLocked(allowedModes="
                        + Arrays.toString(allowedModes) + ")");
            }
            int[] allowedPhysIndexes = new int[allowedModes.length];
            int size = 0;
            for (int modeId : allowedModes) {
                int physIndex = findDisplayInfoIndexLocked(modeId);
                if (physIndex < 0) {
                    Slog.w(TAG, "Requested mode ID " + modeId + " not available,"
                            + " dropping from allowed set.");
                } else {
                    allowedPhysIndexes[size++] = physIndex;
                }
            }

            // If we couldn't find one or more of the suggested allowed modes then we need to
            // shrink the array to its actual size.
            if (size != allowedModes.length) {
                allowedPhysIndexes = Arrays.copyOf(allowedPhysIndexes, size);
            }

            // If we found no suitable modes, then we try again with the default mode which we
            // assume has a suitable physical config.
            if (size == 0) {
                if (DEBUG) {
                    Slog.w(TAG, "No valid modes allowed, falling back to default mode (id="
                            + mDefaultModeId + ")");
                }
                allowedModes = new int[] { mDefaultModeId };
                allowedPhysIndexes = new int[] { findDisplayInfoIndexLocked(mDefaultModeId) };
            }

            mAllowedModeIds = allowedModes;
            mAllowedModeIdsInvalid = false;

            if (Arrays.equals(mAllowedPhysIndexes, allowedPhysIndexes)) {
                return false;
            }
            mAllowedPhysIndexes = allowedPhysIndexes;

            if (DEBUG) {
                Slog.w(TAG, "Setting allowed physical configs: allowedPhysIndexes="
                        + Arrays.toString(allowedPhysIndexes));
            }

            SurfaceControl.setAllowedDisplayConfigs(getDisplayTokenLocked(), allowedPhysIndexes);
            int activePhysIndex = SurfaceControl.getActiveConfig(getDisplayTokenLocked());
            if (activePhysIndex < 0) {
                return false;
            }
            return updateActiveModeLocked(activePhysIndex);
        }

        public boolean requestColorModeLocked(int colorMode) {
            if (mActiveColorMode == colorMode) {
                return false;
            }
            if (!mSupportedColorModes.contains(colorMode)) {
                Slog.w(TAG, "Unable to find color mode " + colorMode
                        + ", ignoring request.");
                return false;
            }
            SurfaceControl.setActiveColorMode(getDisplayTokenLocked(), colorMode);
            mActiveColorMode = colorMode;
            mActiveColorModeInvalid = false;
            return true;
        }

        @Override
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mPhysicalDisplayId=" + mPhysicalDisplayId);
            pw.println("mAllowedPhysIndexes=" + Arrays.toString(mAllowedPhysIndexes));
            pw.println("mAllowedModeIds=" + Arrays.toString(mAllowedModeIds));
            pw.println("mMinRefreshRate=" + mMinRefreshRate);
            pw.println("mMaxRefreshRate=" + mMaxRefreshRate);
            pw.println("mAllowedModeIdsInvalid=" + mAllowedModeIdsInvalid);
            pw.println("mActivePhysIndex=" + mActivePhysIndex);
            pw.println("mActiveModeId=" + mActiveModeId);
            pw.println("mActiveColorMode=" + mActiveColorMode);
            pw.println("mDefaultModeId=" + mDefaultModeId);
            pw.println("mState=" + Display.stateToString(mState));
            pw.println("mBrightness=" + mBrightness);
            pw.println("mBacklight=" + mBacklight);
            pw.println("mDisplayInfos=");
            for (int i = 0; i < mDisplayInfos.length; i++) {
                pw.println("  " + mDisplayInfos[i]);
            }
            pw.println("mSupportedModes=");
            for (int i = 0; i < mSupportedModes.size(); i++) {
                pw.println("  " + mSupportedModes.valueAt(i));
            }
            pw.print("mSupportedColorModes=[");
            for (int i = 0; i < mSupportedColorModes.size(); i++) {
                if (i != 0) {
                    pw.print(", ");
                }
                pw.print(mSupportedColorModes.get(i));
            }
            pw.println("]");
        }

        private int findDisplayInfoIndexLocked(int modeId) {
            DisplayModeRecord record = mSupportedModes.get(modeId);
            if (record != null) {
                for (int i = 0; i < mDisplayInfos.length; i++) {
                    SurfaceControl.PhysicalDisplayInfo info = mDisplayInfos[i];
                    if (record.hasMatchingMode(info)){
                        return i;
                    }
                }
            }
            return -1;
        }

        private int findMatchingModeIdLocked(int physIndex) {
            SurfaceControl.PhysicalDisplayInfo info = mDisplayInfos[physIndex];
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(info)) {
                    return record.mMode.getModeId();
                }
            }
            return 0;
        }

        private void updateDeviceInfoLocked() {
            mInfo = null;
            sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
        }

        private Display.Mode[] getDisplayModes(SparseArray<DisplayModeRecord> records) {
            final int size = records.size();
            Display.Mode[] modes = new Display.Mode[size];
            for (int i = 0; i < size; i++) {
                DisplayModeRecord record = records.valueAt(i);
                modes[i] = record.mMode;
            }
            return modes;
        }

        private boolean isDisplayPrivate(DisplayAddress.Physical physicalAddress) {
            if (physicalAddress == null) {
                return false;
            }
            final Resources res = getOverlayContext().getResources();
            int[] ports = res.getIntArray(
                    com.android.internal.R.array.config_localPrivateDisplayPorts);
            if (ports != null) {
                int port = Byte.toUnsignedInt(physicalAddress.getPort());
                for (int p : ports) {
                    if (p == port) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Supplies a context whose Resources apply runtime-overlays */
    Context getOverlayContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }

    /**
     * Keeps track of a display configuration.
     */
    private static final class DisplayModeRecord {
        public final Display.Mode mMode;

        public DisplayModeRecord(SurfaceControl.PhysicalDisplayInfo phys) {
            mMode = createMode(phys.width, phys.height, phys.refreshRate);
        }

        /**
         * Returns whether the mode generated by the given PhysicalDisplayInfo matches the mode
         * contained by the record modulo mode ID.
         *
         * Note that this doesn't necessarily mean the the PhysicalDisplayInfos are identical, just
         * that they generate identical modes.
         */
        public boolean hasMatchingMode(SurfaceControl.PhysicalDisplayInfo info) {
            int modeRefreshRate = Float.floatToIntBits(mMode.getRefreshRate());
            int displayInfoRefreshRate = Float.floatToIntBits(info.refreshRate);
            return mMode.getPhysicalWidth() == info.width
                    && mMode.getPhysicalHeight() == info.height
                    && modeRefreshRate == displayInfoRefreshRate;
        }

        public String toString() {
            return "DisplayModeRecord{mMode=" + mMode + "}";
        }
    }

    private final class PhysicalDisplayEventReceiver extends DisplayEventReceiver {
        PhysicalDisplayEventReceiver(Looper looper) {
            super(looper, VSYNC_SOURCE_APP, CONFIG_CHANGED_EVENT_DISPATCH);
        }

        @Override
        public void onHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
            synchronized (getSyncRoot()) {
                if (connected) {
                    tryConnectDisplayLocked(physicalDisplayId);
                } else {
                    tryDisconnectDisplayLocked(physicalDisplayId);
                }
            }
        }

        @Override
        public void onConfigChanged(long timestampNanos, long physicalDisplayId, int physIndex) {
            if (DEBUG) {
                Slog.d(TAG, "onConfigChanged("
                        + "timestampNanos=" + timestampNanos
                        + ", physicalDisplayId=" + physicalDisplayId
                        + ", physIndex=" + physIndex + ")");
            }
            synchronized (getSyncRoot()) {
                LocalDisplayDevice device = mDevices.get(physicalDisplayId);
                if (device == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Received config change for unhandled physical display: "
                                + "physicalDisplayId=" + physicalDisplayId);
                    }
                    return;
                }
                device.onActivePhysicalDisplayModeChangedLocked(physIndex);
            }
        }
    }
}
