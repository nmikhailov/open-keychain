/*
 * Copyright (C) 2013-2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2014 Vincent Breitmoser <v.breitmoser@mugenguild.com>
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

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.CertifyResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.pgp.KeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.CachedPublicKeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract.UserPackets;
import org.sufficientlysecure.keychain.provider.KeychainDatabase.Tables;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.CertifyActionsParcel;
import org.sufficientlysecure.keychain.service.CertifyActionsParcel.CertifyAction;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.KeychainIntentServiceHandler;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.ui.adapter.MultiUserIdsAdapter;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.widget.CertifyKeySpinner;
import org.sufficientlysecure.keychain.ui.widget.KeySpinner;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Passphrase;
import org.sufficientlysecure.keychain.util.Preferences;

import java.lang.reflect.Method;
import java.util.ArrayList;

public class CertifyKeyFragment extends LoaderFragment
        implements LoaderManager.LoaderCallbacks<Cursor> {

    public static final int REQUEST_CODE_PASSPHRASE = 0x00008001;

    private CheckBox mUploadKeyCheckbox;
    ListView mUserIds;

    private CertifyKeySpinner mCertifyKeySpinner;

    private long[] mPubMasterKeyIds;

    private long mSignMasterKeyId = Constants.key.none;

    public static final String[] USER_IDS_PROJECTION = new String[]{
            UserPackets._ID,
            UserPackets.MASTER_KEY_ID,
            UserPackets.USER_ID,
            UserPackets.IS_PRIMARY,
            UserPackets.IS_REVOKED
    };
    private static final int INDEX_MASTER_KEY_ID = 1;
    private static final int INDEX_USER_ID = 2;
    private static final int INDEX_IS_PRIMARY = 3;
    private static final int INDEX_IS_REVOKED = 4;

    private MultiUserIdsAdapter mUserIdsAdapter;
    private Messenger mPassthroughMessenger;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Start out with a progress indicator.
        setContentShown(false);

        mPubMasterKeyIds = getActivity().getIntent().getLongArrayExtra(CertifyKeyActivity.EXTRA_KEY_IDS);
        if (mPubMasterKeyIds == null) {
            Log.e(Constants.TAG, "List of key ids to certify missing!");
            getActivity().finish();
            return;
        }

        mPassthroughMessenger = getActivity().getIntent().getParcelableExtra(
                KeychainIntentService.EXTRA_MESSENGER);

        // preselect certify key id if given
        long certifyKeyId = getActivity().getIntent().getLongExtra(CertifyKeyActivity.EXTRA_CERTIFY_KEY_ID, Constants.key.none);
        if (certifyKeyId != Constants.key.none) {
            try {
                CachedPublicKeyRing key = (new ProviderHelper(getActivity())).getCachedPublicKeyRing(certifyKeyId);
                if (key.canCertify()) {
                    mCertifyKeySpinner.setSelectedKeyId(certifyKeyId);
                }
            } catch (PgpKeyNotFoundException e) {
                Log.e(Constants.TAG, "certify certify check failed", e);
            }
        }

        mUserIdsAdapter = new MultiUserIdsAdapter(getActivity(), null, 0);
        mUserIds.setAdapter(mUserIdsAdapter);
        mUserIds.setDividerHeight(0);

        getLoaderManager().initLoader(0, null, this);

        OperationResult result = getActivity().getIntent().getParcelableExtra(CertifyKeyActivity.EXTRA_RESULT);
        if (result != null) {
            // display result from import
            result.createNotify(getActivity()).show();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup superContainer, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, superContainer, savedInstanceState);

        View view = inflater.inflate(R.layout.certify_key_fragment, getContainer());

        mCertifyKeySpinner = (CertifyKeySpinner) view.findViewById(R.id.certify_key_spinner);
        mUploadKeyCheckbox = (CheckBox) view.findViewById(R.id.sign_key_upload_checkbox);
        mUserIds = (ListView) view.findViewById(R.id.view_key_user_ids);

        // make certify image gray, like action icons
        ImageView vActionCertifyImage =
                (ImageView) view.findViewById(R.id.certify_key_action_certify_image);
        vActionCertifyImage.setColorFilter(getResources().getColor(R.color.tertiary_text_light),
                PorterDuff.Mode.SRC_IN);

        mCertifyKeySpinner.setOnKeyChangedListener(new KeySpinner.OnKeyChangedListener() {
            @Override
            public void onKeyChanged(long masterKeyId) {
                mSignMasterKeyId = masterKeyId;
            }
        });

        View vCertifyButton = view.findViewById(R.id.certify_key_certify_button);
        vCertifyButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                if (mSignMasterKeyId == Constants.key.none) {
                    Notify.create(getActivity(), getString(R.string.select_key_to_certify),
                            Notify.Style.ERROR).show();
                } else {
                    initiateCertifying();
                }
            }
        });

        // If this is a debug build, don't upload by default
        if (Constants.DEBUG) {
            mUploadKeyCheckbox.setChecked(false);
        }

        return root;
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Uri uri = UserPackets.buildUserIdsUri();

        String selection, ids[];
        {
            // generate placeholders and string selection args
            ids = new String[mPubMasterKeyIds.length];
            StringBuilder placeholders = new StringBuilder("?");
            for (int i = 0; i < mPubMasterKeyIds.length; i++) {
                ids[i] = Long.toString(mPubMasterKeyIds[i]);
                if (i != 0) {
                    placeholders.append(",?");
                }
            }
            // put together selection string
            selection = UserPackets.IS_REVOKED + " = 0" + " AND "
                    + Tables.USER_PACKETS + "." + UserPackets.MASTER_KEY_ID
                    + " IN (" + placeholders + ")";
        }

        return new CursorLoader(getActivity(), uri,
                USER_IDS_PROJECTION, selection, ids,
                Tables.USER_PACKETS + "." + UserPackets.MASTER_KEY_ID + " ASC"
                        + ", " + Tables.USER_PACKETS + "." + UserPackets.USER_ID + " ASC"
        );
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {

        MatrixCursor matrix = new MatrixCursor(new String[]{
                "_id", "user_data", "grouped"
        }) {
            @Override
            public byte[] getBlob(int column) {
                // For some reason, getBlob was not implemented before ICS
                if (VERSION.SDK_INT < VERSION_CODES.ICE_CREAM_SANDWICH) {
                    try {
                        // haha, yes there is int.class
                        Method m = MatrixCursor.class.getDeclaredMethod("get", new Class[]{int.class});
                        m.setAccessible(true);
                        return (byte[]) m.invoke(this, 1);
                    } catch (Exception e) {
                        throw new UnsupportedOperationException(e);
                    }
                }
                return super.getBlob(column);
            }
        };
        data.moveToFirst();

        long lastMasterKeyId = 0;
        String lastName = "";
        ArrayList<String> uids = new ArrayList<>();

        boolean header = true;

        // Iterate over all rows
        while (!data.isAfterLast()) {
            long masterKeyId = data.getLong(INDEX_MASTER_KEY_ID);
            String userId = data.getString(INDEX_USER_ID);
            KeyRing.UserId pieces = KeyRing.splitUserId(userId);

            // Two cases:

            boolean grouped = masterKeyId == lastMasterKeyId;
            boolean subGrouped = data.isFirst() || grouped && lastName.equals(pieces.name);
            // Remember for next loop
            lastName = pieces.name;

            Log.d(Constants.TAG, Long.toString(masterKeyId, 16) + (grouped ? "grouped" : "not grouped"));

            if (!subGrouped) {
                // 1. This name should NOT be grouped with the previous, so we flush the buffer

                Parcel p = Parcel.obtain();
                p.writeStringList(uids);
                byte[] d = p.marshall();
                p.recycle();

                matrix.addRow(new Object[]{
                        lastMasterKeyId, d, header ? 1 : 0
                });
                // indicate that we have a header for this masterKeyId
                header = false;

                // Now clear the buffer, and add the new user id, for the next round
                uids.clear();

            }

            // 2. This name should be grouped with the previous, just add to buffer
            uids.add(userId);
            lastMasterKeyId = masterKeyId;

            // If this one wasn't grouped, the next one's gotta be a header
            if (!grouped) {
                header = true;
            }

            // Regardless of the outcome, move to next entry
            data.moveToNext();

        }

        // If there is anything left in the buffer, flush it one last time
        if (!uids.isEmpty()) {

            Parcel p = Parcel.obtain();
            p.writeStringList(uids);
            byte[] d = p.marshall();
            p.recycle();

            matrix.addRow(new Object[]{
                    lastMasterKeyId, d, header ? 1 : 0
            });

        }

        mUserIdsAdapter.swapCursor(matrix);
        setContentShown(true, isResumed());
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mUserIdsAdapter.swapCursor(null);
    }

    /**
     * handles the UI bits of the signing process on the UI thread
     */
    private void initiateCertifying() {
        // get the user's passphrase for this key (if required)
        Passphrase passphrase;
        try {
            passphrase = PassphraseCacheService.getCachedPassphrase(getActivity(), mSignMasterKeyId, mSignMasterKeyId);
        } catch (PassphraseCacheService.KeyNotFoundException e) {
            Log.e(Constants.TAG, "Key not found!", e);
            getActivity().finish();
            return;
        }
        if (passphrase == null) {
            Intent intent = new Intent(getActivity(), PassphraseDialogActivity.class);
            intent.putExtra(PassphraseDialogActivity.EXTRA_SUBKEY_ID, mSignMasterKeyId);
            startActivityForResult(intent, REQUEST_CODE_PASSPHRASE);
            // bail out; need to wait until the user has entered the passphrase before trying again
        } else {
            startCertifying();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_PASSPHRASE: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startCertifying();
                }
                return;
            }

            default: {
                super.onActivityResult(requestCode, resultCode, data);
            }
        }
    }

    /**
     * kicks off the actual signing process on a background thread
     */
    private void startCertifying() {
        // Bail out if there is not at least one user id selected
        ArrayList<CertifyAction> certifyActions = mUserIdsAdapter.getSelectedCertifyActions();
        if (certifyActions.isEmpty()) {
            Notify.create(getActivity(), "No identities selected!",
                    Notify.Style.ERROR).show();
            return;
        }

        Bundle data = new Bundle();
        {
            // fill values for this action
            CertifyActionsParcel parcel = new CertifyActionsParcel(mSignMasterKeyId);
            parcel.mCertifyActions.addAll(certifyActions);

            data.putParcelable(KeychainIntentService.CERTIFY_PARCEL, parcel);
            if (mUploadKeyCheckbox.isChecked()) {
                String keyserver = Preferences.getPreferences(getActivity()).getPreferredKeyserver();
                data.putString(KeychainIntentService.UPLOAD_KEY_SERVER, keyserver);
            }
        }

        // Send all information needed to service to sign key in other thread
        Intent intent = new Intent(getActivity(), KeychainIntentService.class);
        intent.setAction(KeychainIntentService.ACTION_CERTIFY_KEYRING);
        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        if (mPassthroughMessenger != null) {
            intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, mPassthroughMessenger);
        } else {

            // Message is received after signing is done in KeychainIntentService
            KeychainIntentServiceHandler saveHandler = new KeychainIntentServiceHandler(getActivity(),
                    getString(R.string.progress_certifying), ProgressDialog.STYLE_SPINNER, true) {
                public void handleMessage(Message message) {
                    // handle messages by standard KeychainIntentServiceHandler first
                    super.handleMessage(message);

                    if (message.arg1 == MessageStatus.OKAY.ordinal()) {
                        Bundle data = message.getData();
                        CertifyResult result = data.getParcelable(CertifyResult.EXTRA_RESULT);

                        Intent intent = new Intent();
                        intent.putExtra(CertifyResult.EXTRA_RESULT, result);
                        getActivity().setResult(Activity.RESULT_OK, intent);
                        getActivity().finish();
                    }
                }
            };

            // Create a new Messenger for the communication back
            Messenger messenger = new Messenger(saveHandler);
            intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

            // show progress dialog
            saveHandler.showProgressDialog(getActivity());
        }

        // start service with intent
        getActivity().startService(intent);

        if (mPassthroughMessenger != null) {
            getActivity().setResult(Activity.RESULT_OK);
            getActivity().finish();
        }

    }

}
