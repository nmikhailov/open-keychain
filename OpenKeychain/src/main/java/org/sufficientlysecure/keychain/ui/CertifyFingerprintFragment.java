/*
 * Copyright (C) 2015 Dominik Schürmann <dominik@dominikschuermann.de>
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

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.util.Log;


public class CertifyFingerprintFragment extends LoaderFragment implements
        LoaderManager.LoaderCallbacks<Cursor> {

    static final int REQUEST_CERTIFY = 1;

    public static final String ARG_DATA_URI = "uri";

    private TextView mFingerprint;

    private static final int LOADER_ID_UNIFIED = 0;

    private Uri mDataUri;

    private View mActionNo;
    private View mActionYes;

    /**
     * Creates new instance of this fragment
     */
    public static CertifyFingerprintFragment newInstance(Uri dataUri) {
        CertifyFingerprintFragment frag = new CertifyFingerprintFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_DATA_URI, dataUri);

        frag.setArguments(args);

        return frag;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup superContainer, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, superContainer, savedInstanceState);
        View view = inflater.inflate(R.layout.certify_fingerprint_fragment, getContainer());

        mActionNo = view.findViewById(R.id.certify_fingerprint_button_no);
        mActionYes = view.findViewById(R.id.certify_fingerprint_button_yes);

        mFingerprint = (TextView) view.findViewById(R.id.certify_fingerprint_fingerprint);

        mActionNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().finish();
            }
        });
        mActionYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                certify(mDataUri);
            }
        });

        return root;
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
            KeyRings._ID, KeyRings.FINGERPRINT,

    };
    static final int INDEX_UNIFIED_FINGERPRINT = 1;

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

    private void certify(Uri dataUri) {
        long keyId = 0;
        try {
            keyId = new ProviderHelper(getActivity())
                    .getCachedPublicKeyRing(dataUri)
                    .extractOrGetMasterKeyId();
        } catch (PgpKeyNotFoundException e) {
            Log.e(Constants.TAG, "key not found!", e);
        }
        Intent certifyIntent = new Intent(getActivity(), CertifyKeyActivity.class);
        certifyIntent.putExtras(getActivity().getIntent());
        certifyIntent.putExtra(CertifyKeyActivity.EXTRA_KEY_IDS, new long[]{keyId});
        startActivityForResult(certifyIntent, REQUEST_CERTIFY);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // always just pass this one through
        if (requestCode == REQUEST_CERTIFY) {
            getActivity().setResult(resultCode, data);
            getActivity().finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

}
