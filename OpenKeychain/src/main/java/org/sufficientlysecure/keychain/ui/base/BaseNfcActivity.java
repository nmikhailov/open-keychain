/*
 * Copyright (C) 2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2015 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 * Copyright (C) 2013-2014 Signe Rüsch
 * Copyright (C) 2013-2014 Philipp Jakubeit
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui.base;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Toast;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.javacard.BaseJavacardDevice;
import org.sufficientlysecure.keychain.javacard.CardException;
import org.sufficientlysecure.keychain.javacard.JavacardDevice;
import org.sufficientlysecure.keychain.javacard.NfcTransport;
import org.sufficientlysecure.keychain.javacard.PinException;
import org.sufficientlysecure.keychain.javacard.UsbTransport;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.CachedPublicKeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.service.PassphraseCacheService.KeyNotFoundException;
import org.sufficientlysecure.keychain.service.UsbMonitorService;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.ui.CreateKeyActivity;
import org.sufficientlysecure.keychain.ui.PassphraseDialogActivity;
import org.sufficientlysecure.keychain.ui.ViewKeyActivity;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.util.Notify.Style;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Passphrase;
import org.sufficientlysecure.keychain.util.Preferences;

import java.io.IOException;

public abstract class BaseNfcActivity extends BaseActivity {

    public static final int REQUEST_CODE_PIN = 1;

    public static final String EXTRA_TAG_HANDLING_ENABLED = "tag_handling_enabled";
    private static final String ACTION_USB_PERMITTED = Constants.PACKAGE_NAME + ".USB_PERMITTED";
    protected JavacardDevice mDevice = null;
    private NfcAdapter mNfcAdapter;
    private boolean mTagHandlingEnabled;
    private byte[] mNfcFingerprints;
    private String mNfcUserId;
    private byte[] mNfcAid;

    public JavacardDevice getDevice() {
        return mDevice;
    }

    /**
     * Override to change UI before NFC handling (UI thread)
     */
    protected void onNfcPreExecute() {
    }

    /**
     * Override to implement NFC operations (background thread)
     */
    protected void doNfcInBackground() throws IOException {
        mNfcFingerprints = mDevice.getFingerprints();
        mNfcUserId = mDevice.getUserId();
        mNfcAid = mDevice.getAid();
    }

    /**
     * Override to handle result of NFC operations (UI thread)
     */
    protected void onNfcPostExecute() throws IOException {
        final long subKeyId = KeyFormattingUtils.getKeyIdFromFingerprint(mNfcFingerprints);

        try {
            CachedPublicKeyRing ring = new ProviderHelper(this).getCachedPublicKeyRing(
                    KeyRings.buildUnifiedKeyRingsFindBySubkeyUri(subKeyId));
            long masterKeyId = ring.getMasterKeyId();

            Intent intent = new Intent(this, ViewKeyActivity.class);
            intent.setData(KeyRings.buildGenericKeyRingUri(masterKeyId));
            intent.putExtra(ViewKeyActivity.EXTRA_NFC_AID, mNfcAid);
            intent.putExtra(ViewKeyActivity.EXTRA_NFC_USER_ID, mNfcUserId);
            intent.putExtra(ViewKeyActivity.EXTRA_NFC_FINGERPRINTS, mNfcFingerprints);
            startActivity(intent);
        } catch (PgpKeyNotFoundException e) {
            Intent intent = new Intent(this, CreateKeyActivity.class);
            intent.putExtra(CreateKeyActivity.EXTRA_NFC_AID, mNfcAid);
            intent.putExtra(CreateKeyActivity.EXTRA_NFC_USER_ID, mNfcUserId);
            intent.putExtra(CreateKeyActivity.EXTRA_NFC_FINGERPRINTS, mNfcFingerprints);
            startActivity(intent);
        }
    }

    /**
     * Override to use something different than Notify (UI thread)
     */
    protected void onNfcError(String error) {
        Notify.create(this, error, Style.WARN).show();
    }

    public void handleIntentInBackground(final Intent intent) {
        // Actual NFC operations are executed in doInBackground to not block the UI thread
        new AsyncTask<Void, Void, Exception>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                onNfcPreExecute();
            }

            @Override
            protected Exception doInBackground(Void... params) {
                try {
                    handleTagDiscoveredIntent(intent);
                } catch (CardException e) {
                    return e;
                } catch (IOException e) {
                    return e;
                }

                return null;
            }

            @Override
            protected void onPostExecute(Exception exception) {
                super.onPostExecute(exception);

                if (exception != null) {
                    handleNfcError(exception);
                    return;
                }

                try {
                    onNfcPostExecute();
                } catch (IOException e) {
                    handleNfcError(e);
                }
            }
        }.execute();
    }

    protected void pauseTagHandling() {
        mTagHandlingEnabled = false;
    }

    protected void resumeTagHandling() {
        mTagHandlingEnabled = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check whether we're recreating a previously destroyed instance
        if (savedInstanceState != null) {
            // Restore value of members from saved state
            mTagHandlingEnabled = savedInstanceState.getBoolean(EXTRA_TAG_HANDLING_ENABLED);
        } else {
            mTagHandlingEnabled = true;
        }

        Intent intent = getIntent();
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            throw new AssertionError("should not happen: NfcOperationActivity.onCreate is called instead of onNewIntent!");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(EXTRA_TAG_HANDLING_ENABLED, mTagHandlingEnabled);
    }

    /**
     * This activity is started as a singleTop activity.
     * All new NFC Intents which are delivered to this activity are handled here
     */
    @Override
    public void onNewIntent(final Intent intent) {
        Log.d("USBNFC", "New intent: " + intent.toString());
        if (intent.getAction() == null) {
            return;
        }
        switch (intent.getAction()) {
            case NfcAdapter.ACTION_TAG_DISCOVERED:
                if (mTagHandlingEnabled) {
                    handleIntentInBackground(intent);
                }
                break;
            case UsbMonitorService.ACTION_USB_DEVICE_ATTACHED:
                final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                Intent usbI = new Intent(this, getClass())
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                usbI.setAction(ACTION_USB_PERMITTED);
                usbI.putExtra(UsbManager.EXTRA_DEVICE, device);
                PendingIntent pi = PendingIntent.getActivity(this, 0, usbI, PendingIntent.FLAG_CANCEL_CURRENT);

                getUsbManager().requestPermission(device, pi);
                break;
            case ACTION_USB_PERMITTED:
                handleIntentInBackground(intent);
                break;
            default:
                break;
        }
    }

    private void handleNfcError(Exception e) {
        Log.e(Constants.TAG, "nfc error", e);

        if (e instanceof PinException) {
            handlePinError();
        }

        if (e instanceof TagLostException) {
            onNfcError(getString(R.string.error_nfc_tag_lost));
            return;
        }

        short status;
        if (e instanceof CardException) {
            status = ((CardException) e).getResponseCode();
        } else {
            status = -1;
        }
        // When entering a PIN, a status of 63CX indicates X attempts remaining.
        if ((status & (short) 0xFFF0) == 0x63C0) {
            int tries = status & 0x000F;
            onNfcError(getResources().getQuantityString(R.plurals.error_pin, tries, tries));
            return;
        }

        // Otherwise, all status codes are fixed values.
        switch (status) {
            // These errors should not occur in everyday use; if they are returned, it means we
            // made a mistake sending data to the card, or the card is misbehaving.
            case 0x6A80: {
                onNfcError(getString(R.string.error_nfc_bad_data));
                break;
            }
            case 0x6883: {
                onNfcError(getString(R.string.error_nfc_chaining_error));
                break;
            }
            case 0x6B00: {
                onNfcError(getString(R.string.error_nfc_header, "P1/P2"));
                break;
            }
            case 0x6D00: {
                onNfcError(getString(R.string.error_nfc_header, "INS"));
                break;
            }
            case 0x6E00: {
                onNfcError(getString(R.string.error_nfc_header, "CLA"));
                break;
            }
            // These error conditions are more likely to be experienced by an end user.
            case 0x6285: {
                onNfcError(getString(R.string.error_nfc_terminated));
                break;
            }
            case 0x6700: {
                onNfcError(getString(R.string.error_nfc_wrong_length));
                break;
            }
            case 0x6982: {
                onNfcError(getString(R.string.error_nfc_security_not_satisfied));
                break;
            }
            case 0x6983: {
                onNfcError(getString(R.string.error_nfc_authentication_blocked));
                break;
            }
            case 0x6985: {
                onNfcError(getString(R.string.error_nfc_conditions_not_satisfied));
                break;
            }
            // 6A88 is "Not Found" in the spec, but Yubikey also returns 6A83 for this in some cases.
            case 0x6A88:
            case 0x6A83: {
                onNfcError(getString(R.string.error_nfc_data_not_found));
                break;
            }
            // 6F00 is a JavaCard proprietary status code, SW_UNKNOWN, and usually represents an
            // unhandled exception on the smart card.
            case 0x6F00: {
                onNfcError(getString(R.string.error_nfc_unknown));
                break;
            }
            default: {
                onNfcError(getString(R.string.error_nfc, e.getMessage()));
                break;
            }
        }

    }

    public void handlePinError() {
        toast("Wrong PIN!");
        setResult(RESULT_CANCELED);
        finish();
    }

    /**
     * Called when the system is about to start resuming a previous activity,
     * disables NFC Foreground Dispatch
     */
    public void onPause() {
        super.onPause();
        Log.d(Constants.TAG, "BaseNfcActivity.onPause");

        disableNfcForegroundDispatch();
        disableUsbForegroundDispatch();
    }

    /**
     * Called when the activity will start interacting with the user,
     * enables NFC Foreground Dispatch
     */
    public void onResume() {
        super.onResume();
        Log.d(Constants.TAG, "BaseNfcActivity.onResume");

        enableNfcForegroundDispatch();
        enableUsbForegroundDispatch();
    }

    protected void obtainYubiKeyPin(RequiredInputParcel requiredInput) {
        // shortcut if we only use the default yubikey pin
        Preferences prefs = Preferences.getPreferences(this);
        if (prefs.useDefaultYubiKeyPin()) {
            mDevice.setPin(new Passphrase("123456"));
            return;
        }

        try {
            Passphrase phrase = PassphraseCacheService.getCachedPassphrase(this,
                    requiredInput.getMasterKeyId(), requiredInput.getSubKeyId());
            if (phrase != null) {
                mDevice.setPin(phrase);
                return;
            }

            Intent intent = new Intent(this, PassphraseDialogActivity.class);
            intent.putExtra(PassphraseDialogActivity.EXTRA_REQUIRED_INPUT,
                    RequiredInputParcel.createRequiredPassphrase(requiredInput));
            startActivityForResult(intent, REQUEST_CODE_PIN);
        } catch (KeyNotFoundException e) {
            throw new AssertionError(
                    "tried to find passphrase for non-existing key. this is a programming error!");
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_PIN: {
                if (resultCode != Activity.RESULT_OK) {
                    setResult(resultCode);
                    finish();
                    return;
                }
                CryptoInputParcel input = data.getParcelableExtra(PassphraseDialogActivity.RESULT_CRYPTO_INPUT);
                mDevice.setPin(input.getPassphrase());
                break;
            }

            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Handle NFC communication and return a result.
     * <p/>
     * This method is called by onNewIntent above upon discovery of an NFC tag.
     * It handles initialization and login to the application, subsequently
     * calls either nfcCalculateSignature() or decryptSessionKey(), then
     * finishes the activity with an appropriate result.
     * <p/>
     * On general communication, see also
     * http://www.cardwerk.com/smartcards/smartcard_standard_ISO7816-4_annex-a.aspx
     * <p/>
     * References to pages are generally related to the OpenPGP Application
     * on ISO SmartCard Systems specification.
     */
    protected void handleTagDiscoveredIntent(Intent intent) throws IOException {
        Tag nfcDetectedTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

        if (nfcDetectedTag != null) {
            // Connect to the detected tag, setting a couple of settings
            mDevice = new BaseJavacardDevice(new NfcTransport(IsoDep.get(nfcDetectedTag)));
            mDevice.connectToDevice();
            doNfcInBackground();

        } else if (usbDevice != null) {
            mDevice = new BaseJavacardDevice(new UsbTransport(usbDevice, getUsbManager()));
            mDevice.connectToDevice();
            doNfcInBackground();
        }
    }

    public boolean isNfcConnected() {
        return mDevice.isConnected();
    }

    /**
     * Prints a message to the screen
     *
     * @param text the text which should be contained within the toast
     */
    protected void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /**
     * Receive new NFC Intents to this activity only by enabling foreground dispatch.
     * This can only be done in onResume!
     */
    public void enableNfcForegroundDispatch() {
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (mNfcAdapter == null) {
            return;
        }
        Intent nfcI = new Intent(this, getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent nfcPendingIntent = PendingIntent.getActivity(this, 0, nfcI, PendingIntent.FLAG_CANCEL_CURRENT);
        IntentFilter[] writeTagFilters = new IntentFilter[]{
                new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        };

        // https://code.google.com/p/android/issues/detail?id=62918
        // maybe mNfcAdapter.enableReaderMode(); ?
        try {
            mNfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, writeTagFilters, null);
        } catch (IllegalStateException e) {
            Log.i(Constants.TAG, "NfcForegroundDispatch Error!", e);
        }
        Log.d(Constants.TAG, "NfcForegroundDispatch has been enabled!");
    }

    /**
     * Disable foreground dispatch in onPause!
     */
    public void disableNfcForegroundDispatch() {
        if (mNfcAdapter == null) {
            return;
        }
        mNfcAdapter.disableForegroundDispatch(this);
        Log.d(Constants.TAG, "NfcForegroundDispatch has been disabled!");
    }

    private UsbManager getUsbManager() {
        return (UsbManager) getSystemService(USB_SERVICE);
    }

    private BroadcastReceiver rcv;
    private void enableUsbForegroundDispatch() {
        if (getUsbManager() == null) {
            return;
        }

        final Intent usbI = new Intent(this, getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent nfcPendingIntent = PendingIntent.getActivity(this, 0, usbI, PendingIntent.FLAG_CANCEL_CURRENT);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UsbMonitorService.ACTION_USB_DEVICE_ATTACHED);
//        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(rcv = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                usbI.setAction(intent.getAction());
                usbI.putExtras(intent);
                startActivity(usbI);
            }
        }, intentFilter);

        startService(UsbMonitorService.getIntent(this, getClass()));
        Log.d(Constants.TAG, "UsbForegroundDispatch has been enabled!");
    }

    private void disableUsbForegroundDispatch() {
        if (getUsbManager() == null) {
            return;
        }
        unregisterReceiver(rcv);
        stopService(new Intent(this, UsbMonitorService.class));
        Log.d(Constants.TAG, "UsbForegroundDispatch has been disabled!");
    }
}
