package org.sufficientlysecure.keychain.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.widget.Toast;

import org.sufficientlysecure.keychain.util.Log;

public class UsbMonitorReceiver extends BroadcastReceiver{
    @Override
    public void onReceive(final Context context, final Intent intent) {
        Log.d("USBNFC2", "New intent: " + intent.toString());
        String action = intent.getAction();
        if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)){

            Toast.makeText(context, "Device Detected", Toast.LENGTH_LONG).show();
            Intent newIntent = new Intent();
            newIntent.setAction("com.action.USB_DEVICE_ATTACHED");
            //newIntent.putExtra(UsbManager.EXTRA_DEVICE, usbDevice);

            context.sendBroadcast(newIntent);

        } else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){

            Toast.makeText(context, "Device Detached",Toast.LENGTH_LONG).show();
            Intent newIntent = new Intent();
            newIntent.setAction("com.action.USB_DEVICE_DETACHED");
            context.sendBroadcast(newIntent);
        }
    }
}
