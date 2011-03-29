/*
 * Copyright (C) 2011 Skrilax_CZ
 * Decompilation of Motorola Usb.apk
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
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class UsbListener
	implements Runnable
{
	private OutputStream mOutputStream;
	private UsbService mUsbService;

	public UsbListener(UsbService service)
	{
		mUsbService = service;
	}

	private void handleEvent(String event)
	{
		Log.d("UsbListener", "handleEvent: " + event);
		if (event.length() == 0)
			Log.d("UsbListener", "discard invalid event from USBD");
			
		if (event.equals("cable_connected"))
			mUsbService.handleUsbCableAttachment();
		else if (event.equals("usb_enumerated"))
			mUsbService.handleUsbCableEnumerate();
		else if (event.equals("cable_connected_factory"))
			sendUsbModeSwitchCmd("usb_mode_ngp");
		else if (event.equals("get_descriptor"))
			mUsbService.handleGetDescriptor();
		else if (event.equals("cable_disconnected"))
			mUsbService.handleUsbCableDetachment();
		else if (event.equals("usbd_adb_status_on"))
			mUsbService.handleADBOnOff(true);
		else if (event.equals("usbd_adb_status_off"))
			mUsbService.handleADBOnOff(false);
		else if ((event.equals("usbd_start_ngp")) || (event.equals("usbd_start_mtp")) || (event.equals("usbd_start_msc_mount")) || (event.equals("usbd_start_acm")))
			mUsbService.handleStartService(event);
		else if ((event.equals("usbd_req_switch_ngp")) || (event.equals("usbd_req_switch_mtp")) || (event.equals("usbd_req_switch_msc")) || (event.equals("usbd_req_switch_modem")))
			mUsbService.handleUsbModeSwitchFromUsbd(event);
		else
			mUsbService.handleUsbModeSwitchComplete(event);
	}

	private void listenToSocket()
	{ 
		LocalSocket usbdScoket = null;
	 
		try
		{
			usbdScoket = new LocalSocket();
			LocalSocketAddress.Namespace fsNamespace = LocalSocketAddress.Namespace.FILESYSTEM;
			LocalSocketAddress socketAddress = new LocalSocketAddress("/dev/socket/usbd", fsNamespace);
			usbdScoket.connect(socketAddress);
		
			InputStream usbdInputStream = usbdScoket.getInputStream();
			mOutputStream = usbdScoket.getOutputStream();
		
			byte[] buffer = new byte[100];
		
			while (true)
			{
				int count = usbdInputStream.read(buffer);
		
				if (count >= 0)
				{
					int i = 0;
					int k = 0;
			
					while (i < count)
					{
						if (buffer[i] == 0)
						{
							handleEvent(new String(buffer, k, i - k));
							k = i + 1;
						}
						
						i = i + 1; 
					}
				}
				else
					break; //failure
			}
		
		}
		catch (IOException ex)
		{
			Log.e("UsbListener", "IOException, connect/read socket");
		}
		
		//clean up
		synchronized(this)
		{
			if (mOutputStream != null)
			{
				try
				{
					mOutputStream.close();
				}
				catch (IOException ex)
				{
					Log.w("UsbListener", "IOException closing output stream");
				}	
					
				mOutputStream = null;
			}
		
			if (usbdScoket != null)
			{
				try
				{
					usbdScoket.close();
				}
				catch(IOException ex)
				{
					Log.w("UsbListener", "IOException closing socket");
				}
			}
					
			Log.e("UsbListener", "Failed to connect to usbd", new IllegalStateException());
			SystemClock.sleep(2000);
		}
	}

	private synchronized void writeCommand(String cmd, String arg)
	{
		if (mOutputStream == null)
		{
			Log.e("UsbListener", "No connection to usbd");
			return;
		}

		String line = cmd;
		
		if (arg != null)
			line = cmd + arg + "\0";
		else 
			line = cmd + "\0";

		try
		{
			mOutputStream.write(line.getBytes());
		}
		catch (IOException ex)
		{
			Log.e("UsbListener", "IOException in writeCommand");
		}
	}

	public void run()
	{
		try
		{
			while (true)
				listenToSocket();
		}
		catch (Throwable ex)
		{
			Log.e("UsbListener", "Fatal error " + ex + " in UsbListener thread!");
		}
	}

	public void sendUsbModeSwitchCmd(String cmd)
	{
		Log.d("UsbListener", "received usb mode change command from UI: " + cmd);
		writeCommand(cmd, null);
	}
}
