package org.sufficientlysecure.keychain.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.DeleteResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.operations.results.RevokeResult;
import org.sufficientlysecure.keychain.pgp.KeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.DeleteKeyringParcel;
import org.sufficientlysecure.keychain.service.RevokeKeyringParcel;
import org.sufficientlysecure.keychain.ui.base.CryptoOperationHelper;
import org.sufficientlysecure.keychain.ui.dialog.CustomAlertDialogBuilder;
import org.sufficientlysecure.keychain.util.Log;

import java.util.HashMap;

public class DeleteKeyDialogActivity extends FragmentActivity {
    public static final String EXTRA_DELETE_MASTER_KEY_IDS = "extra_delete_master_key_ids";
    public static final String EXTRA_HAS_SECRET = "extra_has_secret";
    public static final String EXTRA_KEYSERVER = "extra_keyserver";

    private CryptoOperationHelper<DeleteKeyringParcel, DeleteResult> mDeleteOpHelper;
    private CryptoOperationHelper<RevokeKeyringParcel, RevokeResult> mRevokeOpHelper;

    private long[] mMasterKeyIds;
    private boolean mHasSecret;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDeleteOpHelper = new CryptoOperationHelper<>(1, DeleteKeyDialogActivity.this,
                getDeletionCallback(), R.string.progress_deleting);

        mRevokeOpHelper = new CryptoOperationHelper<>(2, this,
                getRevocationCallback(), R.string.progress_revoking_uploading);

        mMasterKeyIds = getIntent().getLongArrayExtra(EXTRA_DELETE_MASTER_KEY_IDS);
        mHasSecret = getIntent().getBooleanExtra(EXTRA_HAS_SECRET, false);

        if (mMasterKeyIds.length > 1 && mHasSecret) {
            throw new AssertionError("Secret keys can be deleted only one at a time!" +
                    " Should be checked before reaching DeleteKeyDialogActivity.");
        }

        if (mMasterKeyIds.length == 1 && mHasSecret) {
            // if mMasterKeyIds.length == 0 we let the DeleteOperation respond
            try {
                HashMap<String, Object> data = new ProviderHelper(this).getUnifiedData(
                        mMasterKeyIds[0], new String[]{
                                KeychainContract.KeyRings.USER_ID,
                                KeychainContract.KeyRings.IS_REVOKED
                        }, new int[]{
                                ProviderHelper.FIELD_TYPE_STRING,
                                ProviderHelper.FIELD_TYPE_INTEGER
                        }
                );
                if ((long) data.get(KeychainContract.KeyRings.IS_REVOKED) > 0) {
                    showNormalDeleteDialog();
                } else {
                    showRevokeDeleteDialog();
                }
            } catch (ProviderHelper.NotFoundException e) {
                Log.e(Constants.TAG,
                        "Secret key to delete not found at DeleteKeyDialogActivity for "
                                + mMasterKeyIds[0], e);
                finish();
                return;
            }
        } else {
            showNormalDeleteDialog();
        }
    }

    private void showNormalDeleteDialog() {

        DeleteKeyDialogFragment deleteKeyDialogFragment
                = DeleteKeyDialogFragment.newInstance(mMasterKeyIds);

        deleteKeyDialogFragment.show(getSupportFragmentManager(), "deleteKeyDialog");

    }

    private void showRevokeDeleteDialog() {

        RevokeDeleteDialogFragment fragment = RevokeDeleteDialogFragment.newInstance();
        fragment.show(getSupportFragmentManager(), "deleteRevokeDialog");
    }

    private void startRevocationOperation() {
        mRevokeOpHelper.cryptoOperation();
    }

    private void startDeletionOperation() {
        mDeleteOpHelper.cryptoOperation();
    }

    private CryptoOperationHelper.Callback<RevokeKeyringParcel, RevokeResult> getRevocationCallback() {

        CryptoOperationHelper.Callback<RevokeKeyringParcel, RevokeResult> callback
                = new CryptoOperationHelper.Callback<RevokeKeyringParcel, RevokeResult>() {
            @Override
            public RevokeKeyringParcel createOperationInput() {
                return new RevokeKeyringParcel(mMasterKeyIds[0], true,
                        getIntent().getStringExtra(EXTRA_KEYSERVER));
            }

            @Override
            public void onCryptoOperationSuccess(RevokeResult result) {
                returnResult(result);
            }

            @Override
            public void onCryptoOperationCancelled() {
                setResult(RESULT_CANCELED);
                finish();
            }

            @Override
            public void onCryptoOperationError(RevokeResult result) {
                returnResult(result);
            }

            @Override
            public boolean onCryptoSetProgress(String msg, int progress, int max) {
                return false;
            }
        };

        return callback;
    }

    private CryptoOperationHelper.Callback<DeleteKeyringParcel, DeleteResult> getDeletionCallback() {

        CryptoOperationHelper.Callback<DeleteKeyringParcel, DeleteResult> callback
                = new CryptoOperationHelper.Callback<DeleteKeyringParcel, DeleteResult>() {
            @Override
            public DeleteKeyringParcel createOperationInput() {
                return new DeleteKeyringParcel(mMasterKeyIds, true);
            }

            @Override
            public void onCryptoOperationSuccess(DeleteResult result) {
                returnResult(result);
            }

            @Override
            public void onCryptoOperationCancelled() {
                setResult(RESULT_CANCELED);
                finish();
            }

            @Override
            public void onCryptoOperationError(DeleteResult result) {
                returnResult(result);
            }

            @Override
            public boolean onCryptoSetProgress(String msg, int progress, int max) {
                return false;
            }
        };

        return callback;
    }

    private void returnResult(OperationResult result) {
        Intent intent = new Intent();
        intent.putExtra(OperationResult.EXTRA_RESULT, result);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mDeleteOpHelper.handleActivityResult(requestCode, resultCode, data);
        mRevokeOpHelper.handleActivityResult(requestCode, resultCode, data);
    }

    public static class DeleteKeyDialogFragment extends DialogFragment {

        private static final String ARG_DELETE_MASTER_KEY_IDS = "delete_master_key_ids";

        private TextView mMainMessage;
        private View mInflateView;

        /**
         * Creates new instance of this delete file dialog fragment
         */
        public static DeleteKeyDialogFragment newInstance(long[] masterKeyIds) {
            DeleteKeyDialogFragment frag = new DeleteKeyDialogFragment();
            Bundle args = new Bundle();

            args.putLongArray(ARG_DELETE_MASTER_KEY_IDS, masterKeyIds);

            frag.setArguments(args);

            return frag;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final FragmentActivity activity = getActivity();

            final long[] masterKeyIds = getArguments().getLongArray(ARG_DELETE_MASTER_KEY_IDS);

            ContextThemeWrapper theme = new ContextThemeWrapper(activity,
                    R.style.Theme_AppCompat_Light_Dialog);

            CustomAlertDialogBuilder builder = new CustomAlertDialogBuilder(theme);

            // Setup custom View to display in AlertDialog
            LayoutInflater inflater = LayoutInflater.from(theme);
            mInflateView = inflater.inflate(R.layout.view_key_delete_fragment, null);
            builder.setView(mInflateView);

            mMainMessage = (TextView) mInflateView.findViewById(R.id.mainMessage);

            final boolean hasSecret;

            // If only a single key has been selected
            if (masterKeyIds.length == 1) {
                long masterKeyId = masterKeyIds[0];

                try {
                    HashMap<String, Object> data = new ProviderHelper(activity).getUnifiedData(
                            masterKeyId, new String[]{
                                    KeychainContract.KeyRings.USER_ID,
                                    KeychainContract.KeyRings.HAS_ANY_SECRET
                            }, new int[]{
                                    ProviderHelper.FIELD_TYPE_STRING,
                                    ProviderHelper.FIELD_TYPE_INTEGER
                            }
                    );
                    String name;
                    KeyRing.UserId mainUserId = KeyRing.splitUserId((String) data.get(KeychainContract.KeyRings.USER_ID));
                    if (mainUserId.name != null) {
                        name = mainUserId.name;
                    } else {
                        name = getString(R.string.user_id_no_name);
                    }
                    hasSecret = ((Long) data.get(KeychainContract.KeyRings.HAS_ANY_SECRET)) == 1;

                    if (hasSecret) {
                        // show title only for secret key deletions,
                        // see http://www.google.com/design/spec/components/dialogs.html#dialogs-behavior
                        builder.setTitle(getString(R.string.title_delete_secret_key, name));
                        mMainMessage.setText(getString(R.string.secret_key_deletion_confirmation, name));
                    } else {
                        mMainMessage.setText(getString(R.string.public_key_deletetion_confirmation, name));
                    }
                } catch (ProviderHelper.NotFoundException e) {
                    dismiss();
                    return null;
                }
            } else {
                mMainMessage.setText(R.string.key_deletion_confirmation_multi);
            }

            builder.setPositiveButton(R.string.btn_delete, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                    ((DeleteKeyDialogActivity) getActivity()).startDeletionOperation();
                }
            });

            builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int id) {
                    getActivity().finish();
                }
            });

            return builder.show();
        }
    }

    public static class RevokeDeleteDialogFragment extends DialogFragment {

        public static RevokeDeleteDialogFragment newInstance() {
            RevokeDeleteDialogFragment frag = new RevokeDeleteDialogFragment();
            return frag;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();

            final String CHOICE_REVOKE = getString(R.string.del_rev_dialog_choice_rev_upload);
            final String CHOICE_DELETE = getString(R.string.del_rev_dialog_choice_delete);

            // if the dialog is displayed from the application class, design is missing
            // hack to get holo design (which is not automatically applied due to activity's Theme.NoDisplay
            ContextThemeWrapper theme = new ContextThemeWrapper(activity,
                    R.style.Theme_AppCompat_Light_Dialog);

            CustomAlertDialogBuilder alert = new CustomAlertDialogBuilder(theme);
            alert.setTitle(R.string.del_rev_dialog_title);

            LayoutInflater inflater = LayoutInflater.from(theme);
            View view = inflater.inflate(R.layout.del_rev_dialog, null);
            alert.setView(view);

            final Spinner spinner = (Spinner) view.findViewById(R.id.spinner);

            alert.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                    activity.setResult(RESULT_CANCELED);
                    activity.finish();
                }
            });

            alert.setPositiveButton(R.string.del_rev_dialog_btn_revoke,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            String choice = spinner.getSelectedItem().toString();
                            if (choice.equals(CHOICE_REVOKE)) {
                                ((DeleteKeyDialogActivity) activity)
                                        .startRevocationOperation();
                            } else if (choice.equals(CHOICE_DELETE)) {
                                ((DeleteKeyDialogActivity) activity)
                                        .startDeletionOperation();
                            } else {
                                throw new AssertionError(
                                        "Unsupported delete type in RevokeDeleteDialogFragment");
                            }
                        }
                    });

            final AlertDialog alertDialog = alert.show();

            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {

                    String choice = parent.getItemAtPosition(pos).toString();

                    if (choice.equals(CHOICE_REVOKE)) {
                        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setText(R.string.del_rev_dialog_btn_revoke);
                    } else if (choice.equals(CHOICE_DELETE)) {
                        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setText(R.string.del_rev_dialog_btn_delete);
                    } else {
                        throw new AssertionError(
                                "Unsupported delete type in RevokeDeleteDialogFragment");
                    }
                }

                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            return alertDialog;
        }
    }

}
