package org.sufficientlysecure.keychain.service;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.os.Parcelable;
import android.widget.Toast;

import org.sufficientlysecure.keychain.util.Log;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

public class UsbMonitorService extends IntentService {
    public static final String ACTION_USB_DEVICE_ATTACHED = "org.sufficientlysecure.keychain.service.ACTION_USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DEVICE_DETACHED = "org.sufficientlysecure.keychain.service.ACTION_USB_DEVICE_DETACHED";
    private static final int SLEEP_TIME = 2 * 1000;
    private static final String ARG_CALLBACK = "cb";

    public static Intent getIntent(final Context context, final Class callback) {
        final Intent intent = new Intent(context, UsbMonitorService.class);
        intent.putExtra(ARG_CALLBACK, callback);
        return intent;
    }

    public UsbMonitorService() {
        super(UsbMonitorService.class.getName());
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        Collection<UsbDevice> oldDevices = manager.getDeviceList().values();
        while (true) {
            final HashMap<String, UsbDevice> devices = manager.getDeviceList();

            for (UsbDevice device: devices.values()) {
                if (!oldDevices.contains(device) && matches(device)) {
                    final Intent in = new Intent(ACTION_USB_DEVICE_ATTACHED);
//                    in.setClass(this, (Class<?>) intent.getSerializableExtra(ARG_CALLBACK));
                    in.putExtra(UsbManager.EXTRA_DEVICE, device);
//                    in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                    startActivity(in);
                    sendBroadcast(in);
                }
            }

            oldDevices = devices.values();

            try {
                Thread.sleep(SLEEP_TIME);
            } catch (InterruptedException e) {
            }
        }
    }

    private boolean matches(final UsbDevice usbDevice) {
        return true;
    }

    /*@Override
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
    }*/
}
