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

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import org.spongycastle.bcpg.sig.KeyFlags;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.EditKeyResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.pgp.KeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.ServiceProgressHandler;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel.Algorithm;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel.ChangeUnlockParcel;
import org.sufficientlysecure.keychain.ui.CreateKeyActivity.FragAction;
import org.sufficientlysecure.keychain.ui.dialog.ProgressDialogFragment;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Preferences;

import java.util.Iterator;

public class CreateKeyFinalFragment extends Fragment {

    public static final int REQUEST_EDIT_KEY = 0x00008007;

    CreateKeyActivity mCreateKeyActivity;

    TextView mNameEdit;
    TextView mEmailEdit;
    CheckBox mUploadCheckbox;
    View mBackButton;
    View mCreateButton;
    TextView mEditText;
    View mEditButton;

    SaveKeyringParcel mSaveKeyringParcel;

    /**
     * Creates new instance of this fragment
     */
    public static CreateKeyFinalFragment newInstance() {
        CreateKeyFinalFragment frag = new CreateKeyFinalFragment();

        Bundle args = new Bundle();
        frag.setArguments(args);

        return frag;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.create_key_final_fragment, container, false);

        mNameEdit = (TextView) view.findViewById(R.id.name);
        mEmailEdit = (TextView) view.findViewById(R.id.email);
        mUploadCheckbox = (CheckBox) view.findViewById(R.id.create_key_upload);
        mBackButton = view.findViewById(R.id.create_key_back_button);
        mCreateButton = view.findViewById(R.id.create_key_next_button);
        mEditText = (TextView) view.findViewById(R.id.create_key_edit_text);
        mEditButton = view.findViewById(R.id.create_key_edit_button);

        // set values
        mNameEdit.setText(mCreateKeyActivity.mName);
        if (mCreateKeyActivity.mAdditionalEmails != null && mCreateKeyActivity.mAdditionalEmails.size() > 0) {
            String emailText = mCreateKeyActivity.mEmail + ", ";
            Iterator<?> it = mCreateKeyActivity.mAdditionalEmails.iterator();
            while (it.hasNext()) {
                Object next = it.next();
                emailText += next;
                if (it.hasNext()) {
                    emailText += ", ";
                }
            }
            mEmailEdit.setText(emailText);
        } else {
            mEmailEdit.setText(mCreateKeyActivity.mEmail);
        }

        mCreateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createKey();
            }
        });

        mBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCreateKeyActivity.loadFragment(null, FragAction.TO_LEFT);
            }
        });

        mEditButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent edit = new Intent(getActivity(), EditKeyActivity.class);
                edit.putExtra(EditKeyActivity.EXTRA_SAVE_KEYRING_PARCEL, mSaveKeyringParcel);
                startActivityForResult(edit, REQUEST_EDIT_KEY);
            }
        });

        return view;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mCreateKeyActivity = (CreateKeyActivity) getActivity();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_EDIT_KEY: {
                if (resultCode == Activity.RESULT_OK) {
                    mSaveKeyringParcel = data.getParcelableExtra(EditKeyActivity.EXTRA_SAVE_KEYRING_PARCEL);
                    mEditText.setText(R.string.create_key_custom);
                }
                break;
            }

            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mCreateKeyActivity = (CreateKeyActivity) getActivity();

        if (mSaveKeyringParcel == null) {
            mSaveKeyringParcel = new SaveKeyringParcel();
            if (mCreateKeyActivity.mUseSmartCardSettings) {
                mSaveKeyringParcel.mAddSubKeys.add(new SaveKeyringParcel.SubkeyAdd(Algorithm.RSA,
                        2048, null, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER, 0L));
                mSaveKeyringParcel.mAddSubKeys.add(new SaveKeyringParcel.SubkeyAdd(Algorithm.RSA,
                        2048, null, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE, 0L));
                mSaveKeyringParcel.mAddSubKeys.add(new SaveKeyringParcel.SubkeyAdd(Algorithm.RSA,
                        2048, null, KeyFlags.AUTHENTICATION, 0L));
                mEditText.setText(R.string.create_key_custom);
            } else {
                mSaveKeyringParcel.mAddSubKeys.add(new SaveKeyringParcel.SubkeyAdd(Algorithm.RSA,
                        4096, null, KeyFlags.CERTIFY_OTHER, 0L));
                mSaveKeyringParcel.mAddSubKeys.add(new SaveKeyringParcel.SubkeyAdd(Algorithm.RSA,
                        4096, null, KeyFlags.SIGN_DATA, 0L));
                mSaveKeyringParcel.mAddSubKeys.add(new SaveKeyringParcel.SubkeyAdd(Algorithm.RSA,
                        4096, null, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE, 0L));
            }
            String userId = KeyRing.createUserId(
                    new KeyRing.UserId(mCreateKeyActivity.mName, mCreateKeyActivity.mEmail, null)
            );
            mSaveKeyringParcel.mAddUserIds.add(userId);
            mSaveKeyringParcel.mChangePrimaryUserId = userId;
            if (mCreateKeyActivity.mAdditionalEmails != null
                    && mCreateKeyActivity.mAdditionalEmails.size() > 0) {
                for (String email : mCreateKeyActivity.mAdditionalEmails) {
                    String thisUserId = KeyRing.createUserId(
                            new KeyRing.UserId(mCreateKeyActivity.mName, email, null)
                    );
                    mSaveKeyringParcel.mAddUserIds.add(thisUserId);
                }
            }
            mSaveKeyringParcel.mNewUnlock = mCreateKeyActivity.mPassphrase != null
                    ? new ChangeUnlockParcel(mCreateKeyActivity.mPassphrase, null)
                    : null;
        }
    }


    private void createKey() {
        Intent intent = new Intent(getActivity(), KeychainIntentService.class);
        intent.setAction(KeychainIntentService.ACTION_EDIT_KEYRING);

        ServiceProgressHandler saveHandler = new ServiceProgressHandler(
                getActivity(),
                getString(R.string.progress_building_key),
                ProgressDialog.STYLE_HORIZONTAL,
                ProgressDialogFragment.ServiceType.KEYCHAIN_INTENT) {
            public void handleMessage(Message message) {
                // handle messages by standard KeychainIntentServiceHandler first
                super.handleMessage(message);

                if (message.arg1 == MessageStatus.OKAY.ordinal()) {
                    // get returned data bundle
                    Bundle returnData = message.getData();
                    if (returnData == null) {
                        return;
                    }
                    final EditKeyResult result =
                            returnData.getParcelable(OperationResult.EXTRA_RESULT);
                    if (result == null) {
                        Log.e(Constants.TAG, "result == null");
                        return;
                    }

                    if (result.mMasterKeyId != null && mUploadCheckbox.isChecked()) {
                        // result will be displayed after upload
                        uploadKey(result);
                    } else {
                        Intent data = new Intent();
                        data.putExtra(OperationResult.EXTRA_RESULT, result);
                        getActivity().setResult(Activity.RESULT_OK, data);
                        getActivity().finish();
                    }
                }
            }
        };

        // fill values for this action
        Bundle data = new Bundle();

        // get selected key entries
        data.putParcelable(KeychainIntentService.EDIT_KEYRING_PARCEL, mSaveKeyringParcel);

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        saveHandler.showProgressDialog(getActivity());

        getActivity().startService(intent);
    }

    // TODO move into EditKeyOperation
    private void uploadKey(final EditKeyResult saveKeyResult) {
        // Send all information needed to service to upload key in other thread
        final Intent intent = new Intent(getActivity(), KeychainIntentService.class);

        intent.setAction(KeychainIntentService.ACTION_UPLOAD_KEYRING);

        // set data uri as path to keyring
        Uri blobUri = KeychainContract.KeyRings.buildUnifiedKeyRingUri(
                saveKeyResult.mMasterKeyId);
        intent.setData(blobUri);

        // fill values for this action
        Bundle data = new Bundle();

        // upload to favorite keyserver
        String keyserver = Preferences.getPreferences(getActivity()).getPreferredKeyserver();
        data.putString(KeychainIntentService.UPLOAD_KEY_SERVER, keyserver);

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        ServiceProgressHandler saveHandler = new ServiceProgressHandler(
                getActivity(),
                getString(R.string.progress_uploading),
                ProgressDialog.STYLE_HORIZONTAL,
                ProgressDialogFragment.ServiceType.KEYCHAIN_INTENT) {
            public void handleMessage(Message message) {
                // handle messages by standard KeychainIntentServiceHandler first
                super.handleMessage(message);

                if (message.arg1 == MessageStatus.OKAY.ordinal()) {
                    // TODO: upload operation needs a result!
                    // TODO: then combine these results
                    //if (result.getResult() == OperationResultParcel.RESULT_OK) {
                    //Notify.create(getActivity(), R.string.key_send_success,
                    //Notify.Style.OK).show();

                    Intent data = new Intent();
                    data.putExtra(OperationResult.EXTRA_RESULT, saveKeyResult);
                    getActivity().setResult(Activity.RESULT_OK, data);
                    getActivity().finish();
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        // show progress dialog
        saveHandler.showProgressDialog(getActivity());

        // start service with intent
        getActivity().startService(intent);
    }

}
