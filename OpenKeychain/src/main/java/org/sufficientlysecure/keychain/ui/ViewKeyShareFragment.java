/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.sufficientlysecure.keychain.ui;

import android.annotation.TargetApi;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.compatibility.ClipboardReflection;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.KeychainContract.Keys;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.ui.dialog.ShareNfcDialogFragment;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.util.QrCodeUtils;
import org.sufficientlysecure.keychain.util.Log;

import java.io.IOException;


public class ViewKeyShareFragment extends LoaderFragment implements
        LoaderManager.LoaderCallbacks<Cursor> {

    public static final String ARG_DATA_URI = "uri";

    private TextView mFingerprint;
    private ImageView mFingerprintQrCode;
    private View mFingerprintShareButton;
    private View mFingerprintClipboardButton;
    private View mKeyShareButton;
    private View mKeyClipboardButton;
    private ImageButton mKeySafeSlingerButton;
    private View mNfcHelpButton;
    private View mNfcPrefsButton;
    private View mKeyUploadButton;

    ProviderHelper mProviderHelper;

    private static final int LOADER_ID_UNIFIED = 0;

    private Uri mDataUri;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup superContainer, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, superContainer, savedInstanceState);
        View view = inflater.inflate(R.layout.view_key_share_fragment, getContainer());

        mProviderHelper = new ProviderHelper(ViewKeyShareFragment.this.getActivity());

        mFingerprint = (TextView) view.findViewById(R.id.view_key_fingerprint);
        mFingerprintQrCode = (ImageView) view.findViewById(R.id.view_key_fingerprint_qr_code_image);
        mFingerprintShareButton = view.findViewById(R.id.view_key_action_fingerprint_share);
        mFingerprintClipboardButton = view.findViewById(R.id.view_key_action_fingerprint_clipboard);
        mKeyShareButton = view.findViewById(R.id.view_key_action_key_share);
        mKeyClipboardButton = view.findViewById(R.id.view_key_action_key_clipboard);
        mKeySafeSlingerButton = (ImageButton) view.findViewById(R.id.view_key_action_key_safeslinger);
        mNfcHelpButton = view.findViewById(R.id.view_key_action_nfc_help);
        mNfcPrefsButton = view.findViewById(R.id.view_key_action_nfc_prefs);
        mKeyUploadButton = view.findViewById(R.id.view_key_action_upload);

        mKeySafeSlingerButton.setColorFilter(getResources().getColor(R.color.tertiary_text_light),
                PorterDuff.Mode.SRC_IN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mNfcPrefsButton.setVisibility(View.VISIBLE);
        } else {
            mNfcPrefsButton.setVisibility(View.GONE);
        }

        mFingerprintQrCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrCodeDialog();
            }
        });

        mFingerprintShareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share(mDataUri, mProviderHelper, true, false);
            }
        });
        mFingerprintClipboardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share(mDataUri, mProviderHelper, true, true);
            }
        });
        mKeyShareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share(mDataUri, mProviderHelper, false, false);
            }
        });
        mKeyClipboardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share(mDataUri, mProviderHelper, false, true);
            }
        });
        mKeySafeSlingerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSafeSlinger(mDataUri);
            }
        });
        mNfcHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNfcHelpDialog();
            }
        });
        mNfcPrefsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNfcPrefs();
            }
        });
        mKeyUploadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uploadToKeyserver();
            }
        });

        return root;
    }

    private void startSafeSlinger(Uri dataUri) {
        long keyId = 0;
        try {
            keyId = new ProviderHelper(getActivity())
                    .getCachedPublicKeyRing(dataUri)
                    .extractOrGetMasterKeyId();
        } catch (PgpKeyNotFoundException e) {
            Log.e(Constants.TAG, "key not found!", e);
        }
        Intent safeSlingerIntent = new Intent(getActivity(), SafeSlingerActivity.class);
        safeSlingerIntent.putExtra(SafeSlingerActivity.EXTRA_MASTER_KEY_ID, keyId);
        startActivityForResult(safeSlingerIntent, 0);
    }

    private void share(Uri dataUri, ProviderHelper providerHelper, boolean fingerprintOnly,
                       boolean toClipboard) {
        try {
            String content;
            if (fingerprintOnly) {
                byte[] data = (byte[]) providerHelper.getGenericData(
                        KeyRings.buildUnifiedKeyRingUri(dataUri),
                        Keys.FINGERPRINT, ProviderHelper.FIELD_TYPE_BLOB);
                String fingerprint = KeyFormattingUtils.convertFingerprintToHex(data);
                if (!toClipboard) {
                    content = Constants.FINGERPRINT_SCHEME + ":" + fingerprint;
                } else {
                    content = fingerprint;
                }
            } else {
                Uri uri = KeychainContract.KeyRingData.buildPublicKeyRingUri(dataUri);
                // get public keyring as ascii armored string
                content = providerHelper.getKeyRingAsArmoredString(uri);
            }

            if (toClipboard) {
                ClipboardReflection.copyToClipboard(getActivity(), content);
                String message;
                if (fingerprintOnly) {
                    message = getResources().getString(R.string.fingerprint_copied_to_clipboard);
                } else {
                    message = getResources().getString(R.string.key_copied_to_clipboard);
                }
                Notify.showNotify(getActivity(), message, Notify.Style.OK);
            } else {
                // Android will fail with android.os.TransactionTooLargeException if key is too big
                // see http://www.lonestarprod.com/?p=34
                if (content.length() >= 86389) {
                    Notify.showNotify(getActivity(), R.string.key_too_big_for_sharing,
                            Notify.Style.ERROR);
                    return;
                }

                // let user choose application
                Intent sendIntent = new Intent(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, content);
                sendIntent.setType("text/plain");
                String title;
                if (fingerprintOnly) {
                    title = getResources().getString(R.string.title_share_fingerprint_with);
                } else {
                    title = getResources().getString(R.string.title_share_key);
                }
                startActivity(Intent.createChooser(sendIntent, title));
            }
        } catch (PgpGeneralException e) {
            Log.e(Constants.TAG, "error processing key!", e);
            Notify.showNotify(getActivity(), R.string.error_key_processing, Notify.Style.ERROR);
        } catch (IOException e) {
            Log.e(Constants.TAG, "error processing key!", e);
            Notify.showNotify(getActivity(), R.string.error_key_processing, Notify.Style.ERROR);
        } catch (ProviderHelper.NotFoundException e) {
            Log.e(Constants.TAG, "key not found!", e);
            Notify.showNotify(getActivity(), R.string.error_key_not_found, Notify.Style.ERROR);
        }
    }

    private void showQrCodeDialog() {
        Intent qrCodeIntent = new Intent(getActivity(), QrCodeViewActivity.class);
        qrCodeIntent.setData(mDataUri);
        startActivity(qrCodeIntent);
    }

    private void showNfcHelpDialog() {
        ShareNfcDialogFragment dialog = ShareNfcDialogFragment.newInstance();
        dialog.show(getActivity().getSupportFragmentManager(), "shareNfcDialog");
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void showNfcPrefs() {
        Intent intentSettings = new Intent(
                Settings.ACTION_NFCSHARING_SETTINGS);
        startActivity(intentSettings);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        Uri dataUri = getArguments().getParcelable(ARG_DATA_URI);
        if (dataUri == null) {
            Log.e(Constants.TAG, "Data missing. Should be Uri of key!");
            getActivity().finish();
            return;
        }

        loadData(dataUri);
    }

    private void loadData(Uri dataUri) {
        mDataUri = dataUri;

        Log.i(Constants.TAG, "mDataUri: " + mDataUri.toString());

        // Prepare the loaders. Either re-connect with an existing ones,
        // or start new ones.
        getLoaderManager().initLoader(LOADER_ID_UNIFIED, null, this);
    }

    static final String[] UNIFIED_PROJECTION = new String[]{
            KeyRings._ID, KeyRings.MASTER_KEY_ID, KeyRings.HAS_ANY_SECRET,
            KeyRings.USER_ID, KeyRings.FINGERPRINT,
            KeyRings.ALGORITHM, KeyRings.KEY_SIZE, KeyRings.CREATION, KeyRings.EXPIRY,

    };
    static final int INDEX_UNIFIED_MASTER_KEY_ID = 1;
    static final int INDEX_UNIFIED_HAS_ANY_SECRET = 2;
    static final int INDEX_UNIFIED_USER_ID = 3;
    static final int INDEX_UNIFIED_FINGERPRINT = 4;
    static final int INDEX_UNIFIED_ALGORITHM = 5;
    static final int INDEX_UNIFIED_KEY_SIZE = 6;
    static final int INDEX_UNIFIED_CREATION = 7;
    static final int INDEX_UNIFIED_EXPIRY = 8;

    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        setContentShown(false);
        switch (id) {
            case LOADER_ID_UNIFIED: {
                Uri baseUri = KeyRings.buildUnifiedKeyRingUri(mDataUri);
                return new CursorLoader(getActivity(), baseUri, UNIFIED_PROJECTION, null, null, null);
            }

            default:
                return null;
        }
    }

    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        /* TODO better error handling? May cause problems when a key is deleted,
         * because the notification triggers faster than the activity closes.
         */
        // Avoid NullPointerExceptions...
        if (data.getCount() == 0) {
            return;
        }
        // Swap the new cursor in. (The framework will take care of closing the
        // old cursor once we return.)
        switch (loader.getId()) {
            case LOADER_ID_UNIFIED: {
                if (data.moveToFirst()) {

                    byte[] fingerprintBlob = data.getBlob(INDEX_UNIFIED_FINGERPRINT);
                    String fingerprint = KeyFormattingUtils.convertFingerprintToHex(fingerprintBlob);
                    mFingerprint.setText(KeyFormattingUtils.colorizeFingerprint(fingerprint));

                    loadQrCode(fingerprint);

                    break;
                }
            }

        }
        setContentShown(true);
    }

    /**
     * This is called when the last Cursor provided to onLoadFinished() above is about to be closed.
     * We need to make sure we are no longer using it.
     */
    public void onLoaderReset(Loader<Cursor> loader) {
    }

    /**
     * Load QR Code asynchronously and with a fade in animation
     *
     * @param fingerprint
     */
    private void loadQrCode(final String fingerprint) {
        AsyncTask<Void, Void, Bitmap> loadTask =
                new AsyncTask<Void, Void, Bitmap>() {
                    protected Bitmap doInBackground(Void... unused) {
                        String qrCodeContent = Constants.FINGERPRINT_SCHEME + ":" + fingerprint;
                        // render with minimal size
                        return QrCodeUtils.getQRCodeBitmap(qrCodeContent, 0);
                    }

                    protected void onPostExecute(Bitmap qrCode) {
                        // only change view, if fragment is attached to activity
                        if (ViewKeyShareFragment.this.isAdded()) {

                            // scale the image up to our actual size. we do this in code rather
                            // than let the ImageView do this because we don't require filtering.
                            Bitmap scaled = Bitmap.createScaledBitmap(qrCode,
                                    mFingerprintQrCode.getHeight(), mFingerprintQrCode.getHeight(),
                                    false);
                            mFingerprintQrCode.setImageBitmap(scaled);

                            // simple fade-in animation
                            AlphaAnimation anim = new AlphaAnimation(0.0f, 1.0f);
                            anim.setDuration(200);
                            mFingerprintQrCode.startAnimation(anim);
                        }
                    }
                };

        loadTask.execute();
    }

    private void uploadToKeyserver() {
        Intent uploadIntent = new Intent(getActivity(), UploadKeyActivity.class);
        uploadIntent.setData(mDataUri);
        startActivityForResult(uploadIntent, 0);
    }

}
