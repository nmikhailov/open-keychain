/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.sufficientlysecure.keychain.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.compatibility.DialogFragmentWorkaround;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.util.Log;

public class PassphraseDialogFragment extends DialogFragment implements OnEditorActionListener {
    private static final String ARG_MESSENGER = "messenger";
    private static final String ARG_SECRET_KEY_ID = "secret_key_id";

    public static final int MESSAGE_OKAY = 1;
    public static final int MESSAGE_CANCEL = 2;

    public static final String MESSAGE_DATA_PASSPHRASE = "passphrase";

    private Messenger mMessenger;
    private EditText mPassphraseEditText;

    /**
     * Shows passphrase dialog to cache a new passphrase the user enters for using it later for
     * encryption. Based on mSecretKeyId it asks for a passphrase to open a private key or it asks
     * for a symmetric passphrase
     */
    public static void show(final FragmentActivity context, final long keyId, final Handler returnHandler) {
        // Create a new Messenger for the communication back
        final Messenger messenger = new Messenger(returnHandler);

        DialogFragmentWorkaround.INTERFACE.runnableRunDelayed(new Runnable() {
            public void run() {
                try {
                    PassphraseDialogFragment passphraseDialog = PassphraseDialogFragment.newInstance(context,
                            messenger, keyId);

                    passphraseDialog.show(context.getSupportFragmentManager(), "passphraseDialog");
                } catch (PgpGeneralException e) {
                    Log.d(Constants.TAG, "No passphrase for this secret key!");
                    // send message to handler to start encryption directly
                    returnHandler.sendEmptyMessage(PassphraseDialogFragment.MESSAGE_OKAY);
                }
            }
        });
    }

    /**
     * Creates new instance of this dialog fragment
     *
     * @param secretKeyId secret key id you want to use
     * @param messenger   to communicate back after caching the passphrase
     * @return
     * @throws PgpGeneralException
     */
    public static PassphraseDialogFragment newInstance(Context context, Messenger messenger,
                                                       long secretKeyId) throws PgpGeneralException {
        // check if secret key has a passphrase
        if (!(secretKeyId == Constants.key.symmetric || secretKeyId == Constants.key.none)) {
            try {
                if (!new ProviderHelper(context).getCanonicalizedSecretKeyRing(secretKeyId).hasPassphrase()) {
                    throw new PgpGeneralException("No passphrase! No passphrase dialog needed!");
                }
            } catch (ProviderHelper.NotFoundException e) {
                throw new PgpGeneralException("Error: Key not found!", e);
            }
        }

        PassphraseDialogFragment frag = new PassphraseDialogFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_SECRET_KEY_ID, secretKeyId);
        args.putParcelable(ARG_MESSENGER, messenger);

        frag.setArguments(args);

        return frag;
    }

    /**
     * Creates dialog
     */
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity activity = getActivity();
        final long secretKeyId = getArguments().getLong(ARG_SECRET_KEY_ID);
        mMessenger = getArguments().getParcelable(ARG_MESSENGER);

        CustomAlertDialogBuilder alert = new CustomAlertDialogBuilder(activity);

        alert.setTitle(R.string.title_authentication);

        final CanonicalizedSecretKeyRing secretRing;
        String userId;

        if (secretKeyId == Constants.key.symmetric || secretKeyId == Constants.key.none) {
            alert.setMessage(R.string.passphrase_for_symmetric_encryption);
            secretRing = null;
        } else {
            try {
                ProviderHelper helper = new ProviderHelper(activity);
                secretRing = helper.getCanonicalizedSecretKeyRing(secretKeyId);
                // yes the inner try/catch block is necessary, otherwise the final variable
                // above can't be statically verified to have been set in all cases because
                // the catch clause doesn't return.
                try {
                    userId = secretRing.getPrimaryUserIdWithFallback();
                } catch (PgpGeneralException e) {
                    userId = null;
                }
            } catch (ProviderHelper.NotFoundException e) {
                alert.setTitle(R.string.title_key_not_found);
                alert.setMessage(getString(R.string.key_not_found, secretKeyId));
                alert.setPositiveButton(android.R.string.ok, new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dismiss();
                    }
                });
                alert.setCancelable(false);
                return alert.create();
            }

            Log.d(Constants.TAG, "User id: '" + userId + "'");
            alert.setMessage(getString(R.string.passphrase_for, userId));
        }

        LayoutInflater inflater = activity.getLayoutInflater();
        View view = inflater.inflate(R.layout.passphrase_dialog, null);
        alert.setView(view);

        mPassphraseEditText = (EditText) view.findViewById(R.id.passphrase_passphrase);

        alert.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int id) {
                dismiss();

                String passphrase = mPassphraseEditText.getText().toString();

                // Early breakout if we are dealing with a symmetric key
                if (secretRing == null) {
                    PassphraseCacheService.addCachedPassphrase(activity, Constants.key.symmetric,
                            passphrase, getString(R.string.passp_cache_notif_pwd));
                    // also return passphrase back to activity
                    Bundle data = new Bundle();
                    data.putString(MESSAGE_DATA_PASSPHRASE, passphrase);
                    sendMessageToHandler(MESSAGE_OKAY, data);
                    return;
                }

                CanonicalizedSecretKey unlockedSecretKey = null;

                for (CanonicalizedSecretKey clickSecretKey : secretRing.secretKeyIterator()) {
                    try {
                        boolean unlocked = clickSecretKey.unlock(passphrase);
                        if (unlocked) {
                            unlockedSecretKey = clickSecretKey;
                            break;
                        }
                    } catch (PgpGeneralException e) {
                        Toast.makeText(activity, R.string.error_could_not_extract_private_key,
                                Toast.LENGTH_SHORT).show();

                        sendMessageToHandler(MESSAGE_CANCEL);
                        return; // ran out of keys to try
                    }
                }

                // Means we got an exception every time
                if (unlockedSecretKey == null) {
                    Toast.makeText(activity, R.string.wrong_passphrase,
                            Toast.LENGTH_SHORT).show();

                    sendMessageToHandler(MESSAGE_CANCEL);
                    return;
                }

                long masterKeyId = secretRing.getMasterKeyId();

                // cache the new passphrase
                Log.d(Constants.TAG, "Everything okay! Caching entered passphrase");

                try {
                    PassphraseCacheService.addCachedPassphrase(activity, masterKeyId, passphrase,
                            secretRing.getPrimaryUserIdWithFallback());
                } catch (PgpGeneralException e) {
                    Log.e(Constants.TAG, "adding of a passphrase failed", e);
                }

                if (unlockedSecretKey.getKeyId() != masterKeyId) {
                    PassphraseCacheService.addCachedPassphrase(
                            activity, unlockedSecretKey.getKeyId(), passphrase,
                            unlockedSecretKey.getPrimaryUserIdWithFallback());
                }

                // also return passphrase back to activity
                Bundle data = new Bundle();
                data.putString(MESSAGE_DATA_PASSPHRASE, passphrase);
                sendMessageToHandler(MESSAGE_OKAY, data);
            }
        });

        alert.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });

        // Hack to open keyboard.
        // This is the only method that I found to work across all Android versions
        // http://turbomanage.wordpress.com/2012/05/02/show-soft-keyboard-automatically-when-edittext-receives-focus/
        // Notes: * onCreateView can't be used because we want to add buttons to the dialog
        //        * opening in onActivityCreated does not work on Android 4.4
        mPassphraseEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mPassphraseEditText.post(new Runnable() {
                    @Override
                    public void run() {
                        InputMethodManager imm = (InputMethodManager) getActivity()
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(mPassphraseEditText, InputMethodManager.SHOW_IMPLICIT);
                    }
                });
            }
        });
        mPassphraseEditText.requestFocus();

        mPassphraseEditText.setImeActionLabel(getString(android.R.string.ok), EditorInfo.IME_ACTION_DONE);
        mPassphraseEditText.setOnEditorActionListener(this);

        return alert.show();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);

        dismiss();
        sendMessageToHandler(MESSAGE_CANCEL);
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        Log.d(Constants.TAG, "onDismiss");

        // hide keyboard on dismiss
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager inputManager = (InputMethodManager) getActivity()
                .getSystemService(Context.INPUT_METHOD_SERVICE);

        //check if no view has focus:
        View v = getActivity().getCurrentFocus();
        if (v == null)
            return;

        inputManager.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /**
     * Associate the "done" button on the soft keyboard with the okay button in the view
     */
    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (EditorInfo.IME_ACTION_DONE == actionId) {
            AlertDialog dialog = ((AlertDialog) getDialog());
            Button bt = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

            bt.performClick();
            return true;
        }
        return false;
    }

    /**
     * Send message back to handler which is initialized in a activity
     *
     * @param what Message integer you want to send
     */
    private void sendMessageToHandler(Integer what) {
        Message msg = Message.obtain();
        msg.what = what;

        try {
            mMessenger.send(msg);
        } catch (RemoteException e) {
            Log.w(Constants.TAG, "Exception sending message, Is handler present?", e);
        } catch (NullPointerException e) {
            Log.w(Constants.TAG, "Messenger is null!", e);
        }
    }

    /**
     * Send message back to handler which is initialized in a activity
     *
     * @param what Message integer you want to send
     */
    private void sendMessageToHandler(Integer what, Bundle data) {
        Message msg = Message.obtain();
        msg.what = what;
        if (data != null) {
            msg.setData(data);
        }

        try {
            mMessenger.send(msg);
        } catch (RemoteException e) {
            Log.w(Constants.TAG, "Exception sending message, Is handler present?", e);
        } catch (NullPointerException e) {
            Log.w(Constants.TAG, "Messenger is null!", e);
        }
    }

}
