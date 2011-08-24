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

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class UsbListener implements Runnable
{
    private static final String TAG = "UsbListener";

    private LocalSocket mSocket;
    private OutputStream mOutputStream;

    private boolean mRunning;
    private Handler mUiHandler;
    private Handler mWriteHandler;
    private HandlerThread mWriteThread;

    public static final String EVENT_CABLE_CONNECTED = "cable_connected";
    public static final String EVENT_CABLE_CONNECTED_FACTORY = "cable_connected_factory";
    public static final String EVENT_CABLE_DISCONNECTED = "cable_disconnected";
    public static final String EVENT_ENUMERATED = "usb_enumerated";
    public static final String EVENT_GET_DESCRIPTOR = "get_descriptor";
    public static final String EVENT_ADB_ON = "usbd_adb_status_on";
    public static final String EVENT_ADB_OFF = "usbd_adb_status_off";
    public static final String EVENT_START_NGP = "usbd_start_ngp";
    public static final String EVENT_START_MTP = "usbd_start_ngp";
    public static final String EVENT_START_MSC = "usbd_start_msc_mount";
    public static final String EVENT_START_ACM = "usbd_start_acm";
    public static final String EVENT_START_MODEM = "usbd_start_modem";
    public static final String EVENT_START_RNDIS = "usbd_start_rndis";
    private static final String EVENT_START_PREFIX = "usbd_start_";
    public static final String EVENT_REQ_NGP = "usbd_req_switch_ngp";
    public static final String EVENT_REQ_MTP = "usbd_req_switch_mtp";
    public static final String EVENT_REQ_MSC = "usbd_req_switch_msc";
    public static final String EVENT_REQ_ACM = "usbd_req_switch_acm";
    public static final String EVENT_REQ_MODEM = "usbd_req_switch_modem";
    public static final String EVENT_REQ_RNDIS = "usbd_req_switch_rndis";
    public static final String EVENT_REQ_NONE = "usbd_req_switch_none";
    private static final String EVENT_REQ_PREFIX = "usbd_req_switch_";
    private static final String SWITCH_OK_POSTFIX = ":ok";
    private static final String SWITCH_FAIL_POSTFIX = ":fail";

    public static final String MODE_NGP_ADB = "usb_mode_ngp_adb";
    public static final String MODE_MTP_ADB = "usb_mode_mtp_adb";
    public static final String MODE_MSC_ADB = "usb_mode_msc_adb";
    public static final String MODE_RNDIS_ADB = "usb_mode_rndis_adb";
    public static final String MODE_CHARGE_ADB = "usb_mode_charge_adb";
    public static final String MODE_NGP = "usb_mode_ngp";
    public static final String MODE_MTP = "usb_mode_mtp";
    public static final String MODE_MSC = "usb_mode_msc";
    public static final String MODE_RNDIS = "usb_mode_rndis";
    public static final String MODE_MODEM = "usb_mode_modem";
    public static final String MODE_CHARGE = "usb_mode_charge_only";

    public static final String CMD_UNLOAD_DRIVER = "usb_unload_driver";

    public UsbListener(Handler handler) {
        mUiHandler = handler;
        mRunning = true;

        mWriteThread = new HandlerThread("UsbService command writer");
        mWriteThread.start();

        mWriteHandler = new Handler(mWriteThread.getLooper());
    }

    private void handleEvent(String event) {
        Log.d(TAG, "handleEvent: " + event);

        if (event.isEmpty()) {
            Log.d(TAG, "discard invalid event from USBD");
            return;
        }

        if (event.equals(EVENT_CABLE_CONNECTED)) {
            mUiHandler.sendEmptyMessage(UsbService.MSG_CABLE_ATTACHED);
        } else if (event.equals(EVENT_ENUMERATED)) {
            mUiHandler.sendEmptyMessage(UsbService.MSG_ENUMERATED);
        } else if (event.equals(EVENT_CABLE_CONNECTED_FACTORY)) {
            sendUsbModeSwitchCmd(MODE_NGP);
        } else if (event.equals(EVENT_GET_DESCRIPTOR)) {
            mUiHandler.sendEmptyMessage(UsbService.MSG_GET_DESCRIPTOR);
        } else if (event.equals(EVENT_CABLE_DISCONNECTED)) {
            mUiHandler.sendEmptyMessage(UsbService.MSG_CABLE_DETACHED);
        } else if (event.equals(EVENT_ADB_ON)) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(UsbService.MSG_ADB_CHANGE, 1, 0));
        } else if (event.equals(EVENT_ADB_OFF)) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(UsbService.MSG_ADB_CHANGE, 0, 0));
        } else if (event.startsWith(EVENT_START_PREFIX)) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(UsbService.MSG_START_SERVICE, event));
        } else if (event.startsWith(EVENT_REQ_PREFIX)) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(UsbService.MSG_USBD_MODE_SWITCH, event));
        } else if (event.contains(SWITCH_OK_POSTFIX)) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(UsbService.MSG_MODE_SWITCH_COMPLETE, 1, 0));
        } else if (event.contains(SWITCH_FAIL_POSTFIX)) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(UsbService.MSG_MODE_SWITCH_COMPLETE, 0, 0));
        } else {
            Log.e(TAG, "Got invalid event " + event);
        }
    }

    private synchronized void openSocket() throws IOException {
        LocalSocketAddress socketAddress =
                new LocalSocketAddress("/dev/socket/usbd", LocalSocketAddress.Namespace.FILESYSTEM);

        mSocket = new LocalSocket();
        mSocket.connect(socketAddress);
        mOutputStream = mSocket.getOutputStream();
    }

    private void listenToSocket() throws IOException {
        InputStream usbdInputStream = mSocket.getInputStream();
        byte[] buffer = new byte[100];

        while (true) {
            int count = usbdInputStream.read(buffer);

            if (count < 0) {
                throw new IOException("Unexpected end of stream");
            }

            int pos, start;
            for (pos = 0, start = 0; pos < count; pos++) {
                if (buffer[pos] == 0) {
                    handleEvent(new String(buffer, start, pos - start));
                    start = pos + 1;
                }
            }
        }
    }

    private synchronized void closeSocket() {
        try {
            if (mOutputStream != null) {
                mOutputStream.close();
            }
            mOutputStream = null;

            if (mSocket != null) {
                mSocket.close();
            }
            mSocket = null;
        } catch (IOException e) {
            Log.w(TAG, "IOException during cleanup", e);
        }
    }

    private synchronized void writeCommand(String cmd, String arg) {
        if (mOutputStream == null) {
            Log.e(TAG, "No connection to usbd");
            return;
        }

        String line = cmd;
        if (arg != null) {
            line += arg;
        }
        line += '\0';

        Log.d(TAG, "Writing command " + line + " to usbd");

        try {
            mOutputStream.write(line.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "IOException in writeCommand", e);
        }
    }

    public void run() {
        while (mRunning) {
            try {
                openSocket();
                listenToSocket();
                closeSocket();
            } catch (IOException e) {
                if (mRunning) {
                    Log.w(TAG, "I/O error in socket communication, retrying...", e);
                    /* do a little rate limitation */
                    SystemClock.sleep(2000);
                }
            } catch (Exception e) {
                Log.e(TAG, "Fatal error in UsbListener thread!", e);
                return;
            }
        }
    }

    public synchronized void stop() {
        Log.d(TAG, "Stopping...");
        mRunning = false;
        if (mSocket != null) {
            try {
                /* forcably stop socket communication */
                mSocket.shutdownInput();
            } catch (IOException e) {
                Log.w(TAG, "I/O error during listener shutdown", e);
            }
        }
        mWriteThread.quit();
    }

    public void sendUsbModeSwitchCmd(final String cmd) {
        if (cmd != null) {
            Log.d(TAG, "received usb mode change command from UI: " + cmd);
            mWriteHandler.post(new Runnable() {
                @Override
                public void run() {
                    writeCommand(cmd, null);
                }
            });
        }
    }
}
