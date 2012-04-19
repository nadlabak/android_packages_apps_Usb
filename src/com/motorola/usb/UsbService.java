/*
 * Copyright (C) 2011 Skrilax_CZ
 * Based on Motorola Usb.apk
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

package com.motorola.usb;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.ITelephony;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class UsbService extends Service
{
    private static final String TAG = "MotUsbService";

    /* intent actions that UsbService accepts */
    public static final String ACTION_LAUNCH_SERVICE =
            "com.motorola.intent.action.USB_LAUNCH_USBSERVICE";
    public static final String ACTION_MODE_SWITCH_FROM_UI =
            "com.motorola.intent.action.USB_MODE_SWITCH_FROM_UI";
    public static final String ACTION_MODE_SWITCH_FROM_ATCMD =
            "com.motorola.intent.action.USB_MODE_SWITCH_FROM_ATCMD";
    public static final String ACTION_CABLE_ATTACHED =
            "com.motorola.intent.action.USB_CABLE_ATTACHED";
    public static final String ACTION_CABLE_DETACHED =
            "com.motorola.intent.action.USB_CABLE_DETACHED";
    private static final String ACTION_ATCMD_CLOSED =
            "com.motorola.intent.action.USB_ATCMD_DEV_CLOSED";
    private static final String ACTION_MTP_CLOSED =
            "com.motorola.intent.action.USB_MTP_EXIT_OK";
    private static final String ACTION_RNDIS_CLOSED =
            "com.motorola.intent.action.USB_RNDIS_EXIT_OK";
    private static final String ACTION_USB_REQUEST_RECONFIGURE =
            "com.android.internal.usb.request_reconfigure";

    /* intent actions used for sending to other services */
    public static final String ACTION_LAUNCH_MTP =
            "com.motorola.intent.action.USB_MTP_CONNECTION";
    public static final String ACTION_LAUNCH_ATCMD =
            "com.motorola.intent.action.USB_ATCMD_SERVICE_START";
    private static final String ACTION_STOP_ATCMD =
        "com.motorola.intent.action.USB_ATCMD_SERVICE_STOP_OR_CLOSEDEV";
    public static final String ACTION_LAUNCH_RNDIS =
            "com.motorola.intent.action.USB_RNDIS_CONNECTION";
    private static final String ACTION_ENTER_MSC =
            "com.motorola.intent.action.USB_ENTER_MSC_MODE";
    private static final String ACTION_EXIT_MSC =
            "com.motorola.intent.action.USB_EXIT_MSC_MODE";
    private static final String ACTION_USBLAN_UP =
            "com.motorola.intent.action.USBLANUP";
    private static final String ACTION_USBLAN_DOWN =
            "com.motorola.intent.action.USBLANDOWN";
    private static final String ACTION_USB_RECONFIGURED =
            "com.android.internal.usb.reconfigured";

    /* intent extras */
    public static final String EXTRA_MODE_SWITCH_MODE = "USB_MODE_INDEX";
    public static final String EXTRA_ERROR_MODE_STRING = "USB_MODE_STRING";
    public static final String EXTRA_TETHERING_STATE = "state";
    private static final String EXTRA_RECONFIGURE_CONNECTED = "connected";
    private static final String EXTRA_RECONFIGURE_CONFIGURED = "configured";
    private static final String EXTRA_RECONFIGURE_FUNCTIONS = "functions";
    private static final String EXTRA_RECONFIGURE_REQ_PERMANENT = "permanent";
    private static final String EXTRA_RECONFIGURE_REQ_ADB = "enable_adb";

    private static class ModeInfo {
        String name;
        String mode;
        String adbMode;

        public ModeInfo(String name, String mode, String adbMode) {
            this.name = name;
            this.mode = mode;
            this.adbMode = adbMode;
        }
    }

    private static final HashMap<Integer, ModeInfo> sModes = new HashMap<Integer, ModeInfo>();

    public static final int USB_MODE_ACM_ETH = 0;
    public static final int USB_MODE_MTP = 1;
    public static final int USB_MODE_MSC = 2;
    public static final int USB_MODE_RNDIS = 3;
    public static final int USB_MODE_ACM = 4;
    public static final int USB_MODE_CHARGE_ONLY = 5;

    static {
        sModes.put(USB_MODE_ACM_ETH, new ModeInfo(
                    "Motorola Phone Tools", UsbListener.MODE_ACM_ETH, UsbListener.MODE_ACM_ETH_ADB));
        sModes.put(USB_MODE_MTP, new ModeInfo(
                    "Windows Media Sync", UsbListener.MODE_MTP, UsbListener.MODE_MTP_ADB));
        sModes.put(USB_MODE_MSC, new ModeInfo(
                    "Memory Card", UsbListener.MODE_MSC, UsbListener.MODE_MSC_ADB));
        sModes.put(USB_MODE_RNDIS, new ModeInfo(
                    "USB Networking", UsbListener.MODE_RNDIS, UsbListener.MODE_RNDIS_ADB));
        /* there is no working modem + ADB mode */
        sModes.put(USB_MODE_ACM, new ModeInfo(
                    "Phone as Modem", UsbListener.MODE_ACM, UsbListener.MODE_ACM));
        sModes.put(USB_MODE_CHARGE_ONLY, new ModeInfo(
                    "None", UsbListener.MODE_CHARGE_ONLY, UsbListener.MODE_CHARGE_ADB));
    }

    /* states */
    public static final int USB_STATE_IDLE = 0;
    public static final int USB_STATE_WAIT_ENUM = 1;
    public static final int USB_STATE_SERVICE_STARTUP = 2;
    public static final int USB_STATE_SWITCH_DEVNOD_CLOSE = 3;
    public static final int USB_STATE_DETACH_DEVNOD_CLOSE = 4;
    public static final int USB_STATE_ATTACH_DEVNOD_CLOSE = 5;

    /* switch reasons */
    public static final int USB_SWITCH_FROM_IDLE = -1;
    public static final int USB_SWITCH_FROM_UI = 0;
    public static final int USB_SWITCH_FROM_AT_CMD = 1;
    public static final int USB_SWITCH_FROM_ADB = 2;
    public static final int USB_SWITCH_FROM_USBD = 3;

    /* events */
    private static final int EVENT_CABLE_INSERTED = 0;
    private static final int EVENT_ENUMERATED = 1;
    private static final int EVENT_ENUMERATION_OK = 2;
    private static final int EVENT_ENUMERATION_FAILED = 3;
    private static final int EVENT_CABLE_REMOVED = 4;
    private static final int EVENT_START_SERVICE = 5;
    private static final int EVENT_DEVNODE_CLOSED = 6;
    private static final int EVENT_SWITCH = 7;

    /* messages accepted by handler */
    public static final int MSG_CABLE_ATTACHED = 1;
    public static final int MSG_CABLE_DETACHED = 2;
    public static final int MSG_ENUMERATED = 3;
    public static final int MSG_GET_DESCRIPTOR = 4;
    public static final int MSG_ADB_CHANGE = 5;
    public static final int MSG_START_SERVICE = 6;
    public static final int MSG_USBD_MODE_SWITCH = 7;
    public static final int MSG_MODE_SWITCH_COMPLETE = 8;

    private int mUsbState = USB_STATE_IDLE;
    private int mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
    private int mNewUsbMode = USB_MODE_CHARGE_ONLY;

    private boolean mUsbCableAttached = false;
    private boolean mUsbLanIntentSent = false;
    private boolean mADBEnabled = false;
    private boolean mAtCmdServiceStopped = false;
    private boolean mMtpServiceStopped = false;
    private boolean mRndisServiceStopped = false;
    private int mADBStatusChangeMissedNumber = 0;
    private boolean mMediaMountedReceiverRegistered = false;
    private boolean mPreparingUms = false;

    private UsbListener mUsbListener;
    private File mCurrentStateFile;
    private Notification mNotification;
    private PendingIntent mModeSelectionIntent;
    private Timer mWaitForDevCloseTimer;

    private ITelephony mPhoneService;
    private StorageManager mStorageManager;
    private NotificationManager mNotifManager;

    private HandlerThread mStorageThread;
    private Handler mStorageHandler;
    private Toast mConnectedToast;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CABLE_ATTACHED:
                    Log.d(TAG, "handleUsbCableAttachment()");
                    UsbSettings.writeMode(UsbService.this, -1, false);
                    handleUsbEvent(EVENT_CABLE_INSERTED);
                    break;
                case MSG_ENUMERATED:
                    Log.d(TAG, "handleUsbCableEnumerate()");
                    mUsbCableAttached = true;
                    handleUsbEvent(EVENT_ENUMERATED);
                    break;
                case MSG_GET_DESCRIPTOR:
                    handleGetDescriptor();
                    break;
                case MSG_CABLE_DETACHED:
                    handleUsbCableDetachment();
                    break;
                case MSG_ADB_CHANGE:
                    handleAdbStateChange(msg.arg1 != 0);
                    break;
                case MSG_START_SERVICE:
                    handleStartService((String) msg.obj);
                    break;
                case MSG_USBD_MODE_SWITCH:
                    handleUsbModeSwitchFromUsbd((String) msg.obj);
                    break;
                case MSG_MODE_SWITCH_COMPLETE:
                    boolean success = msg.arg1 != 0;
                    handleUsbEvent(success ? EVENT_ENUMERATION_OK : EVENT_ENUMERATION_FAILED);
                    break;
            }
        }
    };

    private BroadcastReceiver mUsbServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "onReceive(), received intent:" + action);

            if (action.equals(ACTION_ATCMD_CLOSED)) {
                mAtCmdServiceStopped = true;
                handleAtCmdMtpDevClosed();
            } else if (action.equals(ACTION_MTP_CLOSED)) {
                mMtpServiceStopped = true;
                handleAtCmdMtpDevClosed();
            } else if (action.equals(ACTION_RNDIS_CLOSED)) {
                mRndisServiceStopped = true;
                handleAtCmdMtpDevClosed();
            } else if (action.equals(ACTION_MODE_SWITCH_FROM_UI)) {
                int index = intent.getIntExtra(EXTRA_MODE_SWITCH_MODE, -1);
                Log.d(TAG, "onReceive(USB_MODE_SWITCH_FROM_UI) mode=" + index);
                setUsbModeFromUI(index);
            } else if (action.equals(ACTION_MODE_SWITCH_FROM_ATCMD)) {
                int index = intent.getIntExtra(EXTRA_MODE_SWITCH_MODE, -1);
                Log.d(TAG, "onReceive(USB_MODE_SWITCH_FROM_ATCMD) mode=" + index);
                if (index >= 0) {
                    setUsbModeFromAtCmd(index);
                }
            } else if (action.equals(ACTION_USB_REQUEST_RECONFIGURE)) {
                final String functions = intent.getStringExtra(EXTRA_RECONFIGURE_FUNCTIONS);
                final boolean permanent = intent.getBooleanExtra(EXTRA_RECONFIGURE_REQ_PERMANENT, false);
                final boolean adb = intent.getBooleanExtra(EXTRA_RECONFIGURE_REQ_ADB, mADBEnabled);
                handleUsbReconfigureRequest(functions, permanent, adb);
            }
        }
    };

    private StorageEventListener mStorageListener = new StorageEventListener() {
        @Override
        public void onStorageStateChanged(String path, String oldState, String newState) {
            if (mPreparingUms && newState.equals(Environment.MEDIA_SHARED)) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(UsbService.this, R.string.ums_ready, Toast.LENGTH_SHORT).show();
                        mPreparingUms = false;
                    }
                });
            }
        }
    };

    private BroadcastReceiver mMediaMountedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "onReceive(), received intent:" + action);

            if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                startService(new Intent(ACTION_LAUNCH_MTP));
            }
        }
    };

    private UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent uevent) {
            Log.d(TAG, "USBLAN Uevent: " + uevent.toString());

            if (Integer.parseInt(uevent.get("USB_CONNECT")) == 1) {
                Log.d(TAG, "USBLAN enabled");

                if (mUsbState == USB_STATE_SERVICE_STARTUP) {
                    sendUsblanUpIntent();
                }
            } else {
                Log.d(TAG, "USBLAN disabled");
                sendUsblanDownIntent();
            }
        }
    };

    @Override
    public IBinder onBind(Intent paramIntent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        super.onCreate();

        updateModeMapOverrides();

        UsbSettings.writeMode(this, -1, false);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_ATCMD_CLOSED);
        intentFilter.addAction(ACTION_MTP_CLOSED);
        intentFilter.addAction(ACTION_RNDIS_CLOSED);
        intentFilter.addAction(ACTION_MODE_SWITCH_FROM_UI);
        intentFilter.addAction(ACTION_MODE_SWITCH_FROM_ATCMD);
        intentFilter.addAction(ACTION_USB_REQUEST_RECONFIGURE);
        registerReceiver(mUsbServiceReceiver, intentFilter);

        if (getResources().getBoolean(R.bool.show_connection_notification)) {
            mNotification = new Notification();
            mNotification.icon = com.android.internal.R.drawable.stat_sys_data_usb;
            mNotification.when = 0;
            mNotification.flags = Notification.FLAG_ONGOING_EVENT;
            mNotification.defaults &= ~Notification.DEFAULT_SOUND;

            Intent intent = new Intent();
            intent.setClass(this, UsbModeSelectionActivity.class);
            mModeSelectionIntent = PendingIntent.getActivity(this, 0, intent, 0);
        }

        mPhoneService = ITelephony.Stub.asInterface(ServiceManager.getService(TELEPHONY_SERVICE));
        mStorageManager = (StorageManager) getSystemService(STORAGE_SERVICE);
        mNotifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mStorageThread = new HandlerThread("Usb switching storage handler");
        mStorageThread.start();
        mStorageHandler = new Handler(mStorageThread.getLooper());

        mStorageManager.registerListener(mStorageListener);

        mUsbListener = new UsbListener(mHandler);
        new Thread(mUsbListener, UsbListener.class.getName()).start();
        mUEventObserver.startObserving("DEVPATH=/devices/virtual/misc/usbnet_enable");

        String stateFileName = getResources().getString(R.string.current_usb_state_file_name);
        if (!TextUtils.isEmpty(stateFileName)) {
            mCurrentStateFile = new File(stateFileName);
            String state = readUsbStateFile();
            if (!state.isEmpty()) {
                setInitialModeFromState(state);
            }
        }
    }

    @Override
    public void onDestroy() {
        mUsbListener.stop();
        mStorageThread.quit();
        unregisterReceiver(mUsbServiceReceiver);
        mStorageManager.unregisterListener(mStorageListener);
        super.onDestroy();
    }

    private synchronized void handleUsbEvent(int event) {
        Log.d(TAG, "handleUsbEvent(), Received event: " + event + ", current state: " + mUsbState);

        int currentMode = getCurrentUsbMode();

        switch (mUsbState) {
            case USB_STATE_IDLE:
                if (event == EVENT_CABLE_INSERTED) {
                    mUsbState = USB_STATE_WAIT_ENUM;
                    mUsbListener.sendUsbModeSwitchCmd(getSwitchCommand(currentMode));
                } else if (event == EVENT_ENUMERATED) {
                    Log.d(TAG, "Idle state, receive USB_CABLE_ENUMERATE_EVENT.");
                    mUsbState = USB_STATE_SERVICE_STARTUP;
                    mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
                }
                break;

            case USB_STATE_WAIT_ENUM:
                if (event == EVENT_CABLE_REMOVED) {
                    if (mIsSwitchFrom == USB_SWITCH_FROM_UI) {
                        usbModeSwitchFail();
                    }

                    mUsbListener.sendUsbModeSwitchCmd(UsbListener.CMD_UNLOAD_DRIVER);
                    mUsbState = USB_STATE_IDLE;
                    mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
                } else if (event == EVENT_ENUMERATION_OK) {
                    if (mIsSwitchFrom == USB_SWITCH_FROM_UI) {
                        Log.d(TAG, "UI switched to mode: " + mNewUsbMode);
                        UsbSettings.writeMode(this, mNewUsbMode, true);
                        UsbSettings.writeMode(this, -1, false);
                    } else if (mIsSwitchFrom == USB_SWITCH_FROM_USBD
                            || mIsSwitchFrom == USB_SWITCH_FROM_AT_CMD) {
                        UsbSettings.writeMode(this, mNewUsbMode, false);
                    }
                    /* we updated the saved config, need to re-fetch */
                    currentMode = getCurrentUsbMode();

                    if (mIsSwitchFrom != USB_SWITCH_FROM_IDLE) {
                        if (mIsSwitchFrom != USB_SWITCH_FROM_ADB) {
                            showConnectedToast(currentMode);
                            setUsbConnectionNotificationVisibility(true);
                            enableInternalDataConnectivity(currentMode != USB_MODE_ACM);
                        }
                        emitReconfigurationIntent(true, true);
                        updateUsbStateFile(true, currentMode);
                    }

                    if (mADBStatusChangeMissedNumber != 0) {
                        mADBStatusChangeMissedNumber = mADBStatusChangeMissedNumber - 1;
                        mADBEnabled = !mADBEnabled;
                        mIsSwitchFrom = USB_SWITCH_FROM_ADB;
                        mUsbState = USB_STATE_SERVICE_STARTUP;
                        handleUsbEvent(EVENT_SWITCH);
                    } else {
                        mUsbState = USB_STATE_SERVICE_STARTUP;
                        mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
                    }
                }
                break;

            case USB_STATE_SERVICE_STARTUP:
                if (event == EVENT_START_SERVICE) {
                    deviceEnumPostAction();
                    showConnectedToast(currentMode);
                } else if (event == EVENT_CABLE_REMOVED) {
                    mUsbState = USB_STATE_DETACH_DEVNOD_CLOSE;
                    deviceEnumPreAction();
                } else if (event == EVENT_SWITCH) {
                    mUsbState = USB_STATE_SWITCH_DEVNOD_CLOSE;
                    deviceEnumPreAction();
                }
                break;

            case USB_STATE_SWITCH_DEVNOD_CLOSE:
                if (event == EVENT_CABLE_REMOVED) {
                    if (mIsSwitchFrom == USB_SWITCH_FROM_UI) {
                        usbModeSwitchFail();
                    }

                    mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
                    mUsbState = USB_STATE_DETACH_DEVNOD_CLOSE;
                } else if (event == EVENT_DEVNODE_CLOSED) {
                    mUsbState = USB_STATE_WAIT_ENUM;

                    if (mIsSwitchFrom == USB_SWITCH_FROM_UI
                            || mIsSwitchFrom == USB_SWITCH_FROM_AT_CMD
                            || mIsSwitchFrom == USB_SWITCH_FROM_USBD) {
                        mUsbListener.sendUsbModeSwitchCmd(getSwitchCommand(mNewUsbMode));
                    } else if (mIsSwitchFrom == USB_SWITCH_FROM_ADB) {
                        mUsbListener.sendUsbModeSwitchCmd(getSwitchCommand(currentMode));
                    }
                }
                break;

            case USB_STATE_DETACH_DEVNOD_CLOSE:
                if (event == EVENT_CABLE_INSERTED) {
                    mUsbState = USB_STATE_ATTACH_DEVNOD_CLOSE;
                } else if (event == EVENT_DEVNODE_CLOSED) {
                    mUsbListener.sendUsbModeSwitchCmd(UsbListener.CMD_UNLOAD_DRIVER);
                    mUsbState = USB_STATE_IDLE;
                    mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
                }
                break;

            case USB_STATE_ATTACH_DEVNOD_CLOSE:
                if (event == EVENT_CABLE_REMOVED) {
                    mUsbState = USB_STATE_DETACH_DEVNOD_CLOSE;
                } else if (event == EVENT_DEVNODE_CLOSED) {
                    mUsbState = USB_STATE_WAIT_ENUM;
                    mUsbListener.sendUsbModeSwitchCmd(getSwitchCommand(currentMode));
                }
                break;
        }
    }

    private void deviceEnumPreAction() {
        Log.d(TAG, "deviceEnumPreAction()");

        switch (getCurrentUsbMode()) {
            case USB_MODE_ACM_ETH:
                startWaitDevNodeClosedTimer();
                stopAtCmdService();
                stopMtpService();
                break;

            case USB_MODE_MTP:
                startWaitDevNodeClosedTimer();
                stopMtpService();
                break;

            case USB_MODE_MSC:
                changeMassStorageMode(false);
                handleUsbEvent(EVENT_DEVNODE_CLOSED);
                break;

            case USB_MODE_RNDIS:
                startWaitDevNodeClosedTimer();
                stopRndisService();
                break;

            case USB_MODE_ACM:
                startWaitDevNodeClosedTimer();
                stopAtCmdService();
                break;

            default:
                handleUsbEvent(EVENT_DEVNODE_CLOSED);
                break;
        }
    }

    private void deviceEnumPostAction() {
        Log.d(TAG, "deviceEnumPostAction()");

        switch (getCurrentUsbMode()) {
            case USB_MODE_ACM_ETH:
                startAtCmdService();
                startMtpService();
                break;

            case USB_MODE_MTP:
                startMtpService();
                break;

            case USB_MODE_MSC:
                changeMassStorageMode(true);
                break;

            case USB_MODE_RNDIS:
                startRndisService();
                break;

            case USB_MODE_ACM:
                startAtCmdService();
                break;
        }
    }

    private void startAtCmdService() {
        Log.d(TAG, "startAtCmdService()");
        /* fail gracefully if service does not exist */
        try {
            startService(new Intent(ACTION_LAUNCH_ATCMD));
        } catch (Exception e) {
            Log.d(TAG, "Starting AT command service failed: " + e);
        }
    }

    private void stopAtCmdService() {
        Log.d(TAG, "stopAtCmdService()");
        sendBroadcast(new Intent(ACTION_STOP_ATCMD));
    }

    private void startMtpService() {
        Log.d(TAG, "startMtpService()");
        String storageState = Environment.getExternalStorageState();
        boolean busy = storageState.equals(Environment.MEDIA_SHARED) ||
                storageState.equals(Environment.MEDIA_CHECKING);

        if (busy) {
            Log.d(TAG, "startMtpService(), sd card currently shared or checking");
            IntentFilter intentFilter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
            intentFilter.addDataScheme("file");
            registerReceiver(mMediaMountedReceiver, intentFilter);
            mMediaMountedReceiverRegistered = true;
        } else {
            try {
                startService(new Intent(ACTION_LAUNCH_MTP));
            } catch (Exception e) {
                Log.d(TAG, "Starting MTP service failed: " + e);
            }
        }
    }

    private void stopMtpService() {
        Log.d(TAG, "stopMtpService()");
        stopService(new Intent(ACTION_LAUNCH_MTP));

        if (mMediaMountedReceiverRegistered) {
            unregisterReceiver(mMediaMountedReceiver);
            mMediaMountedReceiverRegistered = false;
        }
    }

    private void startRndisService() {
        Log.d(TAG, "startRndisService()");
        try {
            startService(new Intent(ACTION_LAUNCH_RNDIS));
        } catch (Exception e) {
            Log.d(TAG, "Starting RNDIS service failed: " + e);
        }
    }

    private void stopRndisService() {
        Log.d(TAG, "stopRndisService()");
        stopService(new Intent(ACTION_LAUNCH_RNDIS));
    }

    private void sendUsblanDownIntent() {
        Log.d(TAG, "sendUsblanDownIntent()");

        if (mUsbLanIntentSent) {
            Intent intent = new Intent(ACTION_USBLAN_DOWN);
            sendBroadcast(intent);
            mUsbLanIntentSent = false;
        }
    }

    private void sendUsblanUpIntent() {
        Log.d(TAG, "sendUsblanUpIntent()");

        if (getCurrentUsbMode() == USB_MODE_ACM_ETH) {
            Intent intent = new Intent(ACTION_USBLAN_UP);
            sendBroadcast(intent);
            mUsbLanIntentSent = true;
        }
    }

    private void startWaitDevNodeClosedTimer() {
        Log.d(TAG, "startWaitDevNodeClosedTimer()");
        mWaitForDevCloseTimer = new Timer();
        mWaitForDevCloseTimer.schedule(new TimerTask() {
            public void run() {
                Log.d(TAG, "Timeout waiting for service shutdown");
                mAtCmdServiceStopped = false;
                mMtpServiceStopped = false;
                mRndisServiceStopped = false;
                handleUsbEvent(EVENT_DEVNODE_CLOSED);
            }
        }, 2000);
    }

    private void stopWaitDevNodeClosedTimer() {
        Log.d(TAG, "stopWaitDevNodeClosedTimer()");
        mWaitForDevCloseTimer.cancel();
    }

    private void usbModeSwitchFail() {
        Log.d(TAG, "usbModeSwitchFail()");

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClass(this, UsbErrorActivity.class);

        int resId = getStringResForMode(mNewUsbMode);
        if (resId >= 0) {
            intent.putExtra(EXTRA_ERROR_MODE_STRING, getString(resId));
        }
        startActivity(intent);
    }

    private void handleAtCmdMtpDevClosed() {
        Log.d(TAG, "handleAtCmdMtpDevClosed()");

        if ((mUsbState != USB_STATE_SWITCH_DEVNOD_CLOSE)
                && (mUsbState != USB_STATE_DETACH_DEVNOD_CLOSE)
                && (mUsbState != USB_STATE_ATTACH_DEVNOD_CLOSE)) {
            mAtCmdServiceStopped = false;
            mMtpServiceStopped = false;
            mRndisServiceStopped = false;
        }

        int currentMode = getCurrentUsbMode();

        if (currentMode == USB_MODE_ACM_ETH && mAtCmdServiceStopped && mMtpServiceStopped) {
            stopWaitDevNodeClosedTimer();
            mAtCmdServiceStopped = false;
            mMtpServiceStopped = false;
            handleUsbEvent(EVENT_DEVNODE_CLOSED);
        } else if (currentMode == USB_MODE_MTP && mMtpServiceStopped) {
            stopWaitDevNodeClosedTimer();
            mMtpServiceStopped = false;
            handleUsbEvent(EVENT_DEVNODE_CLOSED);
        } else if (currentMode == USB_MODE_RNDIS && mRndisServiceStopped) {
            stopWaitDevNodeClosedTimer();
            mRndisServiceStopped = false;
            handleUsbEvent(EVENT_DEVNODE_CLOSED);
        } else if (currentMode == USB_MODE_ACM && mAtCmdServiceStopped) {
            stopWaitDevNodeClosedTimer();
            mAtCmdServiceStopped = false;
            handleUsbEvent(EVENT_DEVNODE_CLOSED);
        }
    }

    private int getCurrentUsbMode() {
        int mode = UsbSettings.readCurrentMode(this);
        if (sModes.get(mode) == null) {
            Log.w(TAG, "Found unknown mode " + mode + ", assuming none.");
            return USB_MODE_CHARGE_ONLY;
        }
        return mode;
    }

    private String getSwitchCommand(int mode) {
        ModeInfo info = sModes.get(mode);
        if (info == null) {
            return null;
        }
        return mADBEnabled ? info.adbMode : info.mode;
    }

    private int getStringResForMode(int mode) {
        switch (mode) {
            case USB_MODE_ACM_ETH: return R.string.usb_mode_acm_eth;
            case USB_MODE_MTP: return R.string.usb_mode_mtp;
            case USB_MODE_MSC: return R.string.usb_mode_msc;
            case USB_MODE_RNDIS: return R.string.usb_mode_rndis;
            case USB_MODE_ACM: return R.string.usb_mode_acm;
            case USB_MODE_CHARGE_ONLY: return R.string.usb_mode_charge_only;
        }

        return -1;
    }

    private void changeMassStorageMode(final boolean enable) {
        Log.d(TAG, "changeMassStorageMode(), enable " + enable);

        mPreparingUms = enable;
        mStorageHandler.post(new Runnable() {
            @Override
            public void run() {
                if (enable) {
                    mStorageManager.enableUsbMassStorage();
                } else {
                    mStorageManager.disableUsbMassStorage();
                }
                sendBroadcast(new Intent(enable ? ACTION_ENTER_MSC : ACTION_EXIT_MSC));
            }
        });
    }

    private void enableInternalDataConnectivity(boolean enable) {
        Log.d(TAG, "enableInternalDataConnectivity(): " + enable);

        try {
            if (enable) {
                mPhoneService.enableDataConnectivity();
            } else {
                mPhoneService.disableDataConnectivity();
            }
        } catch (Exception e) {
            Log.d(TAG, "Switching data connectivity failed", e);
        }
    }

    private String getUsbModeString(int mode) {
        ModeInfo info = sModes.get(mode);
        return info != null ? info.name : null;
    }

    private void setUsbConnectionNotificationVisibility(boolean visible) {
        Log.d(TAG, "setUsbConnectionNotificationVisibility()");

        if (mNotification == null) {
            /* connection notification was disabled by config */
            return;
        }

        if (visible) {
            mNotification.tickerText = getString(R.string.usb_selection_notification_title);

            int messageRes = getCurrentUsbMode() == USB_MODE_ACM
                    ? R.string.usb_selection_notification_message_for_modem
                    : R.string.usb_selection_notification_message;

            mNotification.setLatestEventInfo(this,
                        getString(R.string.usb_selection_notification_title),
                        getString(messageRes),
                        mModeSelectionIntent);

            mNotifManager.notify(mNotification.icon, mNotification);
        } else {
            mNotifManager.cancel(mNotification.icon);
        }
    }

    private void emitReconfigurationIntent(boolean connected, boolean configured) {
        List<String> functions = new ArrayList<String>();
        Intent reconfigureIntent = new Intent(ACTION_USB_RECONFIGURED);

        reconfigureIntent.putExtra(EXTRA_RECONFIGURE_CONNECTED, connected);
        reconfigureIntent.putExtra(EXTRA_RECONFIGURE_CONFIGURED, configured);

        switch (getCurrentUsbMode()) {
            case USB_MODE_ACM_ETH:
            case USB_MODE_MTP:
                functions.add(UsbManager.USB_FUNCTION_MTP);
                break;
            case USB_MODE_MSC:
                functions.add(UsbManager.USB_FUNCTION_MASS_STORAGE);
                break;
            case USB_MODE_RNDIS:
                functions.add(UsbManager.USB_FUNCTION_RNDIS);
                break;
        }
        if (mADBEnabled) {
            functions.add(UsbManager.USB_FUNCTION_ADB);
        }

        if (!functions.isEmpty()) {
            reconfigureIntent.putExtra(EXTRA_RECONFIGURE_FUNCTIONS,
                    TextUtils.join(",", functions));
        }

        sendBroadcast(reconfigureIntent);
    }

    private void updateUsbStateFile(boolean connected, int mode) {
        String state;

        if (mCurrentStateFile == null) {
            return;
        }

        if (connected) {
            state = getSwitchCommand(mode);
            if (state == null) {
                state = "unknown";
            }
        } else {
            state = "disconnected";
        }

        FileOutputStream out = null;
        try {
            out = new FileOutputStream(mCurrentStateFile, false);
            out.write(state.getBytes());
        } catch (Exception e) {
            Log.e(TAG, "Could not write current state to state file", e);
        }

        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                /* ignore, not much we can do anyway */
            }
        }
    }

    private void setUsbModeFromAtCmd(int mode) {
        Log.d(TAG, "setUsbModeFromAtCmd(" + getUsbModeString(mode) + ")");

        if (mUsbState == USB_STATE_SERVICE_STARTUP) {
            if (mode != getCurrentUsbMode()) {
                mIsSwitchFrom = USB_SWITCH_FROM_AT_CMD;
                mNewUsbMode = mode;
                handleUsbEvent(EVENT_SWITCH);
            }
        }
    }

    private void showConnectedToast(int mode) {
        Log.d(TAG, "showConnectedToast(" + getUsbModeString(mode) + ")");

        int resId = getStringResForMode(mode);
        if (resId < 0) {
            return;
        }

        String toast = getString(R.string.usb_toast_connecting, getString(resId));
        int currentMode = getCurrentUsbMode();

        if (currentMode == USB_MODE_ACM) {
            toast += " ";
            toast += getString(R.string.usb_toast_phone_data_disabled);
        } else if (currentMode == USB_MODE_MSC && mPreparingUms) {
            toast += " ";
            toast += getString(R.string.preparing_ums);
        }

        if (mConnectedToast == null) {
            mConnectedToast = Toast.makeText(UsbService.this, toast, Toast.LENGTH_LONG);
        } else {
            mConnectedToast.cancel();
            mConnectedToast.setText(toast);
        }

        mConnectedToast.show();
    }

    private void setUsbModeFromUI(int mode) {
        Log.d(TAG, "setUsbModeFromUI(" + getUsbModeString(mode) + ")");

        mNewUsbMode = mode;

        if (mUsbState == USB_STATE_SERVICE_STARTUP) {
            mIsSwitchFrom = USB_SWITCH_FROM_UI;
            handleUsbEvent(EVENT_SWITCH);
        } else {
            Log.w(TAG, "not in USB_SERVICE_STARTUP_STATE state");
            Log.d(TAG, "will show error dialog");
            usbModeSwitchFail();
        }
    }

    public void handleAdbStateChange(boolean enable) {
        Log.d(TAG, "handleAdbStateChange(" + enable + ")");

        if (mADBEnabled != enable) {
            if (mUsbState != USB_STATE_IDLE) {
                if ((mUsbState == USB_STATE_SERVICE_STARTUP) && (mADBStatusChangeMissedNumber == 0)) {
                    mADBEnabled = enable;
                    mIsSwitchFrom = USB_SWITCH_FROM_ADB;
                    handleUsbEvent(EVENT_SWITCH);
                } else {
                    mADBStatusChangeMissedNumber = mADBStatusChangeMissedNumber + 1;
                }
            } else {
                mADBEnabled = enable;
            }
        }
    }

    public void handleGetDescriptor() {
        Log.d(TAG, "handleGetDescriptor()");
        mUsbCableAttached = true;

        int currentMode = getCurrentUsbMode();
        setUsbConnectionNotificationVisibility(true);
        enableInternalDataConnectivity(currentMode != USB_MODE_ACM);
        sendBroadcast(new Intent(ACTION_CABLE_ATTACHED));
        emitReconfigurationIntent(true, false);
        updateUsbStateFile(true, currentMode);
    }

    public void handleStartService(String event) {
        Log.d(TAG, "handleStartService(), received event " + event);

        if (mUsbState != USB_STATE_SERVICE_STARTUP) {
            return;
        }

        boolean processEvent = false;

        switch (getCurrentUsbMode()) {
            case USB_MODE_ACM_ETH:
                processEvent = event.equals(UsbListener.EVENT_START_ACM_ETH);
                break;
            case USB_MODE_MTP:
                processEvent = event.equals(UsbListener.EVENT_START_MTP);
                break;
            case USB_MODE_MSC:
                processEvent = event.equals(UsbListener.EVENT_START_MSC);
                break;
            case USB_MODE_RNDIS:
                processEvent = event.equals(UsbListener.EVENT_START_RNDIS);
                break;
            case USB_MODE_ACM:
                processEvent = event.equals(UsbListener.EVENT_START_ACM);
                break;
        }
        if (processEvent) {
            handleUsbEvent(EVENT_START_SERVICE);
        }
    }

    public void handleUsbCableDetachment() {
        Log.d(TAG, "handleUsbCableDetachment()");

        if (mUsbCableAttached) {
            mUsbCableAttached = false;
            setUsbConnectionNotificationVisibility(false);
            enableInternalDataConnectivity(true);
            sendBroadcast(new Intent(ACTION_CABLE_DETACHED));
            emitReconfigurationIntent(false, false);
            updateUsbStateFile(false, -1);
        }

        handleUsbEvent(EVENT_CABLE_REMOVED);
    }

    public void handleUsbModeSwitchFromUsbd(String message) {
        Log.d(TAG, "handleUsbModeSwitchFromUsbd(), received auto switch msg:" + message);
        int newUsbMode = getCurrentUsbMode();

        if (message.equals(UsbListener.EVENT_REQ_ACM_ETH)) {
            if (mUsbState != USB_STATE_SERVICE_STARTUP) {
                return;
            }
            newUsbMode = USB_MODE_ACM_ETH;
        } else if (message.equals(UsbListener.EVENT_REQ_MTP)) {
            newUsbMode = USB_MODE_MTP;
        } else if (message.equals(UsbListener.EVENT_REQ_MSC)) {
            newUsbMode = USB_MODE_MSC;
        } else if (message.equals(UsbListener.EVENT_REQ_ACM)) {
            newUsbMode = USB_MODE_ACM;
        } else if (message.equals(UsbListener.EVENT_REQ_RNDIS)) {
            newUsbMode = USB_MODE_RNDIS;
        } else if (message.equals(UsbListener.EVENT_REQ_CHARGE_ONLY)) {
            newUsbMode = USB_MODE_CHARGE_ONLY;
        }

        mIsSwitchFrom = USB_SWITCH_FROM_USBD;
        mNewUsbMode = newUsbMode;
        handleUsbEvent(EVENT_SWITCH);
    }

    private void handleUsbReconfigureRequest(final String functions, final boolean permanent, final boolean adb) {
        Log.d(TAG, "Got reconfiguration request (functions " + functions + " permanent " + permanent + " adb " + adb + ")");

        if (functions == null) {
            UsbSettings.writeMode(this, -1, false);
            mNewUsbMode = getCurrentUsbMode();
            mIsSwitchFrom = USB_SWITCH_FROM_UI;
        } else {
            if (functions.indexOf(UsbManager.USB_FUNCTION_MTP) >= 0) {
                mNewUsbMode = USB_MODE_ACM_ETH;
            } else if (functions.indexOf(UsbManager.USB_FUNCTION_RNDIS) >= 0) {
                mNewUsbMode = USB_MODE_RNDIS;
            } else if (functions.indexOf(UsbManager.USB_FUNCTION_MASS_STORAGE) >= 0) {
                mNewUsbMode = USB_MODE_MSC;
            } else {
                return;
            }

            mADBEnabled = adb;
            if (permanent) {
                UsbSettings.writeMode(this, mNewUsbMode, true);
            }
            mIsSwitchFrom = USB_SWITCH_FROM_USBD;
        }

        handleUsbEvent(EVENT_SWITCH);
    }

    private void updateModeMapOverrides() {
        Resources res = getResources();
        int[] modes = res.getIntArray(R.array.usb_mode_override_values);
        String[] modeValues = res.getStringArray(R.array.usb_mode_override_modes);
        String[] adbModeValues = res.getStringArray(R.array.usb_mode_override_adb_modes);

        if (modes.length != modeValues.length || modes.length != adbModeValues.length) {
            Log.e(TAG, "Found invalid USB mode overrides, ignoring.");
            return;
        }
        if (modes.length == 0) {
            return;
        }

        for (int i = 0; i < modes.length; i++) {
            ModeInfo info = sModes.get(modes[i]);
            if (info == null) {
                Log.e(TAG, "Found invalid mode override value " + modes[i] + ", ignoring.");
                continue;
            }
            info.mode = modeValues[i];
            info.adbMode = adbModeValues[i];
        }
    }

    private String readUsbStateFile() {
        String state="";
        if (mCurrentStateFile == null) {
            return state;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(mCurrentStateFile);
            byte[] buffer = new byte[32];
            if (in.read(buffer) > 0) {
                state = new String(buffer);
            }
            in.close();
        } catch (Exception e) {
        }
        return state.trim();
    }

    private void setInitialModeFromState(String state) {
        int mode = -1;
        if (!state.isEmpty()) {
            for (int i = 0; i < sModes.size(); i++) {
                ModeInfo info = sModes.get(i);
                if (state.equals(info.adbMode) || state.equals(info.mode)) {
                    mode = i;
                    mADBEnabled = state.equals(info.adbMode);
                    break;
                }
            }
        }
        if (mode != -1) {
            Log.d(TAG, "Initial mode read from state file=" + state);
            UsbSettings.writeMode(UsbService.this, mode, false);
            handleUsbEvent(EVENT_CABLE_INSERTED);
        } else {
            Log.e(TAG, "No valid entry for state " + state);
        }
    }
}
