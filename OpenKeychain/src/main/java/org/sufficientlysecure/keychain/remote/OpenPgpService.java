/*
 * Copyright (C) 2013-2014 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.sufficientlysecure.keychain.remote;

import android.app.PendingIntent;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import org.openintents.openpgp.IOpenPgpService;
import org.openintents.openpgp.OpenPgpMetadata;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.spongycastle.util.encoders.Hex;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.ui.NfcActivity;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerify;
import org.sufficientlysecure.keychain.provider.CachedPublicKeyRing;
import org.sufficientlysecure.keychain.provider.KeychainDatabase.Tables;
import org.sufficientlysecure.keychain.operations.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.pgp.PgpHelper;
import org.sufficientlysecure.keychain.pgp.PgpSignEncrypt;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.ApiAccounts;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.remote.ui.RemoteServiceActivity;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.operations.results.OperationResult.LogEntryParcel;
import org.sufficientlysecure.keychain.operations.results.SignEncryptResult;
import org.sufficientlysecure.keychain.ui.ImportKeysActivity;
import org.sufficientlysecure.keychain.ui.PassphraseDialogActivity;
import org.sufficientlysecure.keychain.ui.ViewKeyActivity;
import org.sufficientlysecure.keychain.util.InputData;
import org.sufficientlysecure.keychain.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

public class OpenPgpService extends RemoteService {

    static final String[] EMAIL_SEARCH_PROJECTION = new String[]{
            KeyRings._ID,
            KeyRings.MASTER_KEY_ID,
            KeyRings.IS_EXPIRED,
            KeyRings.IS_REVOKED,
    };

    // do not pre-select revoked or expired keys
    static final String EMAIL_SEARCH_WHERE = Tables.KEYS + "." + KeychainContract.KeyRings.IS_REVOKED
            + " = 0 AND " + KeychainContract.KeyRings.IS_EXPIRED + " = 0";

    /**
     * Search database for key ids based on emails.
     *
     * @param encryptionUserIds
     * @return
     */
    private Intent getKeyIdsFromEmails(Intent data, String[] encryptionUserIds) {
        boolean noUserIdsCheck = (encryptionUserIds == null || encryptionUserIds.length == 0);
        boolean missingUserIdsCheck = false;
        boolean duplicateUserIdsCheck = false;

        ArrayList<Long> keyIds = new ArrayList<Long>();
        ArrayList<String> missingUserIds = new ArrayList<String>();
        ArrayList<String> duplicateUserIds = new ArrayList<String>();
        if (!noUserIdsCheck) {
            for (String email : encryptionUserIds) {
                // try to find the key for this specific email
                Uri uri = KeyRings.buildUnifiedKeyRingsFindByEmailUri(email);
                Cursor cursor = getContentResolver().query(uri, EMAIL_SEARCH_PROJECTION, EMAIL_SEARCH_WHERE, null, null);
                try {
                    // result should be one entry containing the key id
                    if (cursor != null && cursor.moveToFirst()) {
                        long id = cursor.getLong(cursor.getColumnIndex(KeyRings.MASTER_KEY_ID));
                        keyIds.add(id);
                    } else {
                        missingUserIdsCheck = true;
                        missingUserIds.add(email);
                        Log.d(Constants.TAG, "user id missing");
                    }
                    // another entry for this email -> too keys with the same email inside user id
                    if (cursor != null && cursor.moveToNext()) {
                        duplicateUserIdsCheck = true;
                        duplicateUserIds.add(email);

                        // also pre-select
                        long id = cursor.getLong(cursor.getColumnIndex(KeyRings.MASTER_KEY_ID));
                        keyIds.add(id);
                        Log.d(Constants.TAG, "more than one user id with the same email");
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        }

        // convert ArrayList<Long> to long[]
        long[] keyIdsArray = new long[keyIds.size()];
        for (int i = 0; i < keyIdsArray.length; i++) {
            keyIdsArray[i] = keyIds.get(i);
        }

        if (noUserIdsCheck || missingUserIdsCheck || duplicateUserIdsCheck) {
            // allow the user to verify pub key selection

            Intent intent = new Intent(getBaseContext(), RemoteServiceActivity.class);
            intent.setAction(RemoteServiceActivity.ACTION_SELECT_PUB_KEYS);
            intent.putExtra(RemoteServiceActivity.EXTRA_SELECTED_MASTER_KEY_IDS, keyIdsArray);
            intent.putExtra(RemoteServiceActivity.EXTRA_NO_USER_IDS_CHECK, noUserIdsCheck);
            intent.putExtra(RemoteServiceActivity.EXTRA_MISSING_USER_IDS, missingUserIds);
            intent.putExtra(RemoteServiceActivity.EXTRA_DUPLICATE_USER_IDS, duplicateUserIds);
            intent.putExtra(RemoteServiceActivity.EXTRA_DATA, data);

            PendingIntent pi = PendingIntent.getActivity(getBaseContext(), 0,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT);

            // return PendingIntent to be executed by client
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_INTENT, pi);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
            return result;
        } else {
            // everything was easy, we have exactly one key for every email

            if (keyIdsArray.length == 0) {
                Log.e(Constants.TAG, "keyIdsArray.length == 0, should never happen!");
            }

            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_KEY_IDS, keyIdsArray);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
            return result;
        }
    }

    private Intent getNfcSignIntent(Intent data, long keyId, String pin, byte[] hashToSign, int hashAlgo) {
        // build PendingIntent for Yubikey NFC operations
        Intent intent = new Intent(getBaseContext(), NfcActivity.class);
        intent.setAction(NfcActivity.ACTION_SIGN_HASH);
        // pass params through to activity that it can be returned again later to repeat pgp operation
        intent.putExtra(NfcActivity.EXTRA_DATA, data);
        intent.putExtra(NfcActivity.EXTRA_PIN, pin);
        intent.putExtra(NfcActivity.EXTRA_KEY_ID, keyId);

        intent.putExtra(NfcActivity.EXTRA_NFC_HASH_TO_SIGN, hashToSign);
        intent.putExtra(NfcActivity.EXTRA_NFC_HASH_ALGO, hashAlgo);
        PendingIntent pi = PendingIntent.getActivity(getBaseContext(), 0,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        // return PendingIntent to be executed by client
        Intent result = new Intent();
        result.putExtra(OpenPgpApi.RESULT_INTENT, pi);
        result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
        return result;
    }

    private Intent getNfcDecryptIntent(Intent data, long subKeyId, String pin, byte[] encryptedSessionKey) {
        // build PendingIntent for Yubikey NFC operations
        Intent intent = new Intent(getBaseContext(), NfcActivity.class);
        intent.setAction(NfcActivity.ACTION_DECRYPT_SESSION_KEY);
        // pass params through to activity that it can be returned again later to repeat pgp operation
        intent.putExtra(NfcActivity.EXTRA_DATA, data);
        intent.putExtra(NfcActivity.EXTRA_PIN, pin);
        intent.putExtra(NfcActivity.EXTRA_KEY_ID, subKeyId);

        intent.putExtra(NfcActivity.EXTRA_NFC_ENC_SESSION_KEY, encryptedSessionKey);
        PendingIntent pi = PendingIntent.getActivity(getBaseContext(), 0,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        // return PendingIntent to be executed by client
        Intent result = new Intent();
        result.putExtra(OpenPgpApi.RESULT_INTENT, pi);
        result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
        return result;
    }

    private Intent getPassphraseIntent(Intent data, long keyId) {
        // build PendingIntent for passphrase input
        Intent intent = new Intent(getBaseContext(), PassphraseDialogActivity.class);
        intent.putExtra(PassphraseDialogActivity.EXTRA_SUBKEY_ID, keyId);
        // pass params through to activity that it can be returned again later to repeat pgp operation
        intent.putExtra(PassphraseDialogActivity.EXTRA_DATA, data);
        PendingIntent pi = PendingIntent.getActivity(getBaseContext(), 0,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        // return PendingIntent to be executed by client
        Intent result = new Intent();
        result.putExtra(OpenPgpApi.RESULT_INTENT, pi);
        result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
        return result;
    }

    private Intent signImpl(Intent data, ParcelFileDescriptor input,
                            ParcelFileDescriptor output, AccountSettings accSettings) {
        try {
            boolean asciiArmor = data.getBooleanExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);

            byte[] nfcSignedHash = data.getByteArrayExtra(OpenPgpApi.EXTRA_NFC_SIGNED_HASH);
            if (nfcSignedHash != null) {
                Log.d(Constants.TAG, "nfcSignedHash:" + Hex.toHexString(nfcSignedHash));
            } else {
                Log.d(Constants.TAG, "nfcSignedHash: null");
            }

            // carefully: only set if timestamp exists
            Date nfcCreationDate = null;
            long nfcCreationTimestamp = data.getLongExtra(OpenPgpApi.EXTRA_NFC_SIG_CREATION_TIMESTAMP, -1);
            Log.d(Constants.TAG, "nfcCreationTimestamp: " + nfcCreationTimestamp);
            if (nfcCreationTimestamp != -1) {
                nfcCreationDate = new Date(nfcCreationTimestamp);
            }

            // Get Input- and OutputStream from ParcelFileDescriptor
            InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(input);
            OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(output);
            try {
                long inputLength = is.available();
                InputData inputData = new InputData(is, inputLength);

                // Find the appropriate subkey to sign with
                long sigSubKeyId;
                try {
                    CachedPublicKeyRing signingRing =
                            new ProviderHelper(this).getCachedPublicKeyRing(accSettings.getKeyId());
                    sigSubKeyId = signingRing.getSecretSignId();
                } catch (PgpKeyNotFoundException e) {
                    // secret key that is set for this account is deleted?
                    // show account config again!
                    return getCreateAccountIntent(data, getAccountName(data));
                }

                // get passphrase from cache, if key has "no" passphrase, this returns an empty String
                String passphrase;
                if (data.hasExtra(OpenPgpApi.EXTRA_PASSPHRASE)) {
                    passphrase = data.getStringExtra(OpenPgpApi.EXTRA_PASSPHRASE);
                } else {
                    try {
                        passphrase = PassphraseCacheService.getCachedPassphrase(getContext(),
                                accSettings.getKeyId(), sigSubKeyId);
                    } catch (PassphraseCacheService.KeyNotFoundException e) {
                        // should happen earlier, but return again here if it happens
                        return getCreateAccountIntent(data, getAccountName(data));
                    }
                }
                if (passphrase == null) {
                    // get PendingIntent for passphrase input, add it to given params and return to client
                    return getPassphraseIntent(data, sigSubKeyId);
                }

                // sign-only
                PgpSignEncrypt.Builder builder = new PgpSignEncrypt.Builder(
                        this, new ProviderHelper(getContext()), null,
                        inputData, os
                );
                builder.setEnableAsciiArmorOutput(asciiArmor)
                        .setVersionHeader(PgpHelper.getVersionForHeader(this))
                        .setSignatureHashAlgorithm(accSettings.getHashAlgorithm())
                        .setSignatureMasterKeyId(accSettings.getKeyId())
                        .setSignatureSubKeyId(sigSubKeyId)
                        .setSignaturePassphrase(passphrase)
                        .setNfcState(nfcSignedHash, nfcCreationDate);

                // TODO: currently always assume cleartext input, no sign-only of binary currently!
                builder.setCleartextInput(true);

                // execute PGP operation!
                SignEncryptResult pgpResult = builder.build().execute();

                if (pgpResult.isPending()) {
                    if ((pgpResult.getResult() & SignEncryptResult.RESULT_PENDING_PASSPHRASE) ==
                            SignEncryptResult.RESULT_PENDING_PASSPHRASE) {
                        return getPassphraseIntent(data, pgpResult.getKeyIdPassphraseNeeded());
                    } else if ((pgpResult.getResult() & SignEncryptResult.RESULT_PENDING_NFC) ==
                            SignEncryptResult.RESULT_PENDING_NFC) {
                        // return PendingIntent to execute NFC activity
                        // pass through the signature creation timestamp to be used again on second execution
                        // of PgpSignEncrypt when we have the signed hash!
                        data.putExtra(OpenPgpApi.EXTRA_NFC_SIG_CREATION_TIMESTAMP, pgpResult.getNfcTimestamp().getTime());
                        return getNfcSignIntent(data, pgpResult.getNfcKeyId(), pgpResult.getNfcPassphrase(), pgpResult.getNfcHash(), pgpResult.getNfcAlgo());
                    } else {
                        throw new PgpGeneralException(
                                "Encountered unhandled type of pending action not supported by API!");
                    }
                } else if (pgpResult.success()) {
                    // see end of method
                } else {
                    LogEntryParcel errorMsg = pgpResult.getLog().getLast();
                    throw new Exception(getString(errorMsg.mType.getMsgId()));
                }

            } finally {
                is.close();
                os.close();
            }

            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
            return result;
        } catch (Exception e) {
            Log.d(Constants.TAG, "signImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent encryptAndSignImpl(Intent data, ParcelFileDescriptor input,
                                      ParcelFileDescriptor output, AccountSettings accSettings,
                                      boolean sign) {
        try {
            boolean asciiArmor = data.getBooleanExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);
            String originalFilename = data.getStringExtra(OpenPgpApi.EXTRA_ORIGINAL_FILENAME);
            if (originalFilename == null) {
                originalFilename = "";
            }

            // first try to get key ids from non-ambiguous key id extra
            long[] keyIds = data.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS);
            if (keyIds == null) {
                // get key ids based on given user ids
                String[] userIds = data.getStringArrayExtra(OpenPgpApi.EXTRA_USER_IDS);
                // give params through to activity...
                Intent result = getKeyIdsFromEmails(data, userIds);

                if (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0) == OpenPgpApi.RESULT_CODE_SUCCESS) {
                    keyIds = result.getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS);
                } else {
                    // if not success -> result contains a PendingIntent for user interaction
                    return result;
                }
            }

            // build InputData and write into OutputStream
            // Get Input- and OutputStream from ParcelFileDescriptor
            InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(input);
            OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(output);
            try {
                long inputLength = is.available();
                InputData inputData = new InputData(is, inputLength);

                PgpSignEncrypt.Builder builder = new PgpSignEncrypt.Builder(
                        this, new ProviderHelper(getContext()), null, inputData, os
                );
                builder.setEnableAsciiArmorOutput(asciiArmor)
                        .setVersionHeader(PgpHelper.getVersionForHeader(this))
                        .setCompressionId(accSettings.getCompression())
                        .setSymmetricEncryptionAlgorithm(accSettings.getEncryptionAlgorithm())
                        .setEncryptionMasterKeyIds(keyIds)
                        .setFailOnMissingEncryptionKeyIds(true)
                        .setOriginalFilename(originalFilename)
                        .setAdditionalEncryptId(accSettings.getKeyId()); // add acc key for encryption

                if (sign) {

                    // Find the appropriate subkey to sign with
                    long sigSubKeyId;
                    try {
                        CachedPublicKeyRing signingRing =
                                new ProviderHelper(this).getCachedPublicKeyRing(accSettings.getKeyId());
                        sigSubKeyId = signingRing.getSecretSignId();
                    } catch (PgpKeyNotFoundException e) {
                        // secret key that is set for this account is deleted?
                        // show account config again!
                        return getCreateAccountIntent(data, getAccountName(data));
                    }

                    String passphrase;
                    if (data.hasExtra(OpenPgpApi.EXTRA_PASSPHRASE)) {
                        passphrase = data.getStringExtra(OpenPgpApi.EXTRA_PASSPHRASE);
                    } else {
                        passphrase = PassphraseCacheService.getCachedPassphrase(getContext(),
                                accSettings.getKeyId(), sigSubKeyId);
                    }
                    if (passphrase == null) {
                        // get PendingIntent for passphrase input, add it to given params and return to client
                        return getPassphraseIntent(data, sigSubKeyId);
                    }

                    byte[] nfcSignedHash = data.getByteArrayExtra(OpenPgpApi.EXTRA_NFC_SIGNED_HASH);
                    // carefully: only set if timestamp exists
                    Date nfcCreationDate = null;
                    long nfcCreationTimestamp = data.getLongExtra(OpenPgpApi.EXTRA_NFC_SIG_CREATION_TIMESTAMP, -1);
                    if (nfcCreationTimestamp != -1) {
                        nfcCreationDate = new Date(nfcCreationTimestamp);
                    }

                    // sign and encrypt
                    builder.setSignatureHashAlgorithm(accSettings.getHashAlgorithm())
                            .setSignatureMasterKeyId(accSettings.getKeyId())
                            .setSignatureSubKeyId(sigSubKeyId)
                            .setSignaturePassphrase(passphrase)
                            .setNfcState(nfcSignedHash, nfcCreationDate);
                }

                // execute PGP operation!
                SignEncryptResult pgpResult = builder.build().execute();

                if (pgpResult.isPending()) {
                    if ((pgpResult.getResult() & SignEncryptResult.RESULT_PENDING_PASSPHRASE) ==
                            SignEncryptResult.RESULT_PENDING_PASSPHRASE) {
                        return getPassphraseIntent(data, pgpResult.getKeyIdPassphraseNeeded());
                    } else if ((pgpResult.getResult() & SignEncryptResult.RESULT_PENDING_NFC) ==
                            SignEncryptResult.RESULT_PENDING_NFC) {
                        // return PendingIntent to execute NFC activity
                        // pass through the signature creation timestamp to be used again on second execution
                        // of PgpSignEncrypt when we have the signed hash!
                        data.putExtra(OpenPgpApi.EXTRA_NFC_SIG_CREATION_TIMESTAMP, pgpResult.getNfcTimestamp().getTime());
                        return getNfcSignIntent(data, pgpResult.getNfcKeyId(), pgpResult.getNfcPassphrase(), pgpResult.getNfcHash(), pgpResult.getNfcAlgo());
                    } else {
                        throw new PgpGeneralException(
                                "Encountered unhandled type of pending action not supported by API!");
                    }
                } else if (pgpResult.success()) {
                    // see end of method
                } else {
                    LogEntryParcel errorMsg = pgpResult.getLog().getLast();
                    throw new Exception(getString(errorMsg.mType.getMsgId()));
                }

            } finally {
                is.close();
                os.close();
            }

            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
            return result;
        } catch (Exception e) {
            Log.d(Constants.TAG, "encryptAndSignImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent decryptAndVerifyImpl(Intent data, ParcelFileDescriptor input,
                                        ParcelFileDescriptor output, Set<Long> allowedKeyIds,
                                        boolean decryptMetadataOnly) {
        try {
            // Get Input- and OutputStream from ParcelFileDescriptor
            InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(input);

            OutputStream os;
            if (decryptMetadataOnly) {
                os = null;
            } else {
                os = new ParcelFileDescriptor.AutoCloseOutputStream(output);
            }

            Intent result = new Intent();
            try {
                String passphrase = data.getStringExtra(OpenPgpApi.EXTRA_PASSPHRASE);
                long inputLength = is.available();
                InputData inputData = new InputData(is, inputLength);

                PgpDecryptVerify.Builder builder = new PgpDecryptVerify.Builder(
                        this, new ProviderHelper(getContext()), null, inputData, os
                );

                byte[] nfcDecryptedSessionKey = data.getByteArrayExtra(OpenPgpApi.EXTRA_NFC_DECRYPTED_SESSION_KEY);

                // allow only private keys associated with accounts of this app
                // no support for symmetric encryption
                builder.setPassphrase(passphrase)
                        .setAllowSymmetricDecryption(false)
                        .setAllowedKeyIds(allowedKeyIds)
                        .setDecryptMetadataOnly(decryptMetadataOnly)
                        .setNfcState(nfcDecryptedSessionKey);

                // TODO: currently does not support binary signed-only content
                DecryptVerifyResult pgpResult = builder.build().execute();

                if (pgpResult.isPending()) {
                    if ((pgpResult.getResult() & DecryptVerifyResult.RESULT_PENDING_ASYM_PASSPHRASE) ==
                            DecryptVerifyResult.RESULT_PENDING_ASYM_PASSPHRASE) {
                        return getPassphraseIntent(data, pgpResult.getKeyIdPassphraseNeeded());
                    } else if ((pgpResult.getResult() & DecryptVerifyResult.RESULT_PENDING_SYM_PASSPHRASE) ==
                            DecryptVerifyResult.RESULT_PENDING_SYM_PASSPHRASE) {
                        throw new PgpGeneralException(
                                "Decryption of symmetric content not supported by API!");
                    } else if ((pgpResult.getResult() & DecryptVerifyResult.RESULT_PENDING_NFC) ==
                            DecryptVerifyResult.RESULT_PENDING_NFC) {
                        return getNfcDecryptIntent(
                                data, pgpResult.getNfcSubKeyId(), pgpResult.getNfcPassphrase(), pgpResult.getNfcEncryptedSessionKey());
                    } else {
                        throw new PgpGeneralException(
                                "Encountered unhandled type of pending action not supported by API!");
                    }
                } else if (pgpResult.success()) {

                    OpenPgpSignatureResult signatureResult = pgpResult.getSignatureResult();
                    if (signatureResult != null) {
                        result.putExtra(OpenPgpApi.RESULT_SIGNATURE, signatureResult);

                        if (data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) < 5) {
                            // SIGNATURE_KEY_REVOKED and SIGNATURE_KEY_EXPIRED have been added in version 5
                            if (signatureResult.getStatus() == OpenPgpSignatureResult.SIGNATURE_KEY_REVOKED
                                    || signatureResult.getStatus() == OpenPgpSignatureResult.SIGNATURE_KEY_EXPIRED) {
                                signatureResult.setStatus(OpenPgpSignatureResult.SIGNATURE_ERROR);
                            }
                        }

                        if (signatureResult.getStatus() == OpenPgpSignatureResult.SIGNATURE_KEY_MISSING) {
                            // If signature is unknown we return an _additional_ PendingIntent
                            // to retrieve the missing key
                            Intent intent = new Intent(getBaseContext(), ImportKeysActivity.class);
                            intent.setAction(ImportKeysActivity.ACTION_IMPORT_KEY_FROM_KEYSERVER_AND_RETURN_TO_SERVICE);
                            intent.putExtra(ImportKeysActivity.EXTRA_KEY_ID, signatureResult.getKeyId());
                            intent.putExtra(ImportKeysActivity.EXTRA_PENDING_INTENT_DATA, data);

                            PendingIntent pi = PendingIntent.getActivity(getBaseContext(), 0,
                                    intent,
                                    PendingIntent.FLAG_CANCEL_CURRENT);

                            result.putExtra(OpenPgpApi.RESULT_INTENT, pi);
                        }
                    }

                    if (data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) >= 4) {
                        OpenPgpMetadata metadata = pgpResult.getDecryptMetadata();
                        if (metadata != null) {
                            result.putExtra(OpenPgpApi.RESULT_METADATA, metadata);
                        }
                    }
                } else {
                    LogEntryParcel errorMsg = pgpResult.getLog().getLast();
                    throw new Exception(getString(errorMsg.mType.getMsgId()));
                }
            } finally {
                is.close();
                if (os != null) {
                    os.close();
                }
            }

            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
            return result;
        } catch (Exception e) {
            Log.d(Constants.TAG, "decryptAndVerifyImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent getKeyImpl(Intent data) {
        try {
            long masterKeyId = data.getLongExtra(OpenPgpApi.EXTRA_KEY_ID, 0);

            try {
                // try to find key, throws NotFoundException if not in db!
                mProviderHelper.getCanonicalizedPublicKeyRing(masterKeyId);

                Intent result = new Intent();
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);

                // also return PendingIntent that opens the key view activity
                Intent intent = new Intent(getBaseContext(), ViewKeyActivity.class);
                intent.setData(KeyRings.buildGenericKeyRingUri(masterKeyId));

                PendingIntent pi = PendingIntent.getActivity(getBaseContext(), 0,
                        intent,
                        PendingIntent.FLAG_CANCEL_CURRENT);

                result.putExtra(OpenPgpApi.RESULT_INTENT, pi);

                return result;
            } catch (ProviderHelper.NotFoundException e) {
                Intent result = new Intent();

                // If keys are not in db we return an additional PendingIntent
                // to retrieve the missing key
                Intent intent = new Intent(getBaseContext(), ImportKeysActivity.class);
                intent.setAction(ImportKeysActivity.ACTION_IMPORT_KEY_FROM_KEYSERVER_AND_RETURN_TO_SERVICE);
                intent.putExtra(ImportKeysActivity.EXTRA_KEY_ID, masterKeyId);
                intent.putExtra(ImportKeysActivity.EXTRA_PENDING_INTENT_DATA, data);

                PendingIntent pi = PendingIntent.getActivity(getBaseContext(), 0,
                        intent,
                        PendingIntent.FLAG_CANCEL_CURRENT);

                result.putExtra(OpenPgpApi.RESULT_INTENT, pi);
                result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED);
                return result;
            }
        } catch (Exception e) {
            Log.d(Constants.TAG, "getKeyImpl", e);
            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_ERROR,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, e.getMessage()));
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }
    }

    private Intent getKeyIdsImpl(Intent data) {
        // if data already contains key ids extra GET_KEY_IDS has been executed again
        // after user interaction. Then, we just need to return the array again!
        if (data.hasExtra(OpenPgpApi.EXTRA_KEY_IDS)) {
            long[] keyIdsArray = data.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS);

            Intent result = new Intent();
            result.putExtra(OpenPgpApi.RESULT_KEY_IDS, keyIdsArray);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS);
            return result;
        } else {
            // get key ids based on given user ids
            String[] userIds = data.getStringArrayExtra(OpenPgpApi.EXTRA_USER_IDS);
            return getKeyIdsFromEmails(data, userIds);
        }
    }

    /**
     * Check requirements:
     * - params != null
     * - has supported API version
     * - is allowed to call the service (access has been granted)
     *
     * @param data
     * @return null if everything is okay, or a Bundle with an error/PendingIntent
     */
    private Intent checkRequirements(Intent data) {
        // params Bundle is required!
        if (data == null) {
            Intent result = new Intent();
            OpenPgpError error = new OpenPgpError(OpenPgpError.GENERIC_ERROR, "params Bundle required!");
            result.putExtra(OpenPgpApi.RESULT_ERROR, error);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }

        // version code is required and needs to correspond to version code of service!
        // History of versions in org.openintents.openpgp.util.OpenPgpApi
        // we support 3, 4, 5
        if (data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) != 3
                && data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) != 4
                && data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) != 5) {
            Intent result = new Intent();
            OpenPgpError error = new OpenPgpError
                    (OpenPgpError.INCOMPATIBLE_API_VERSIONS, "Incompatible API versions!\n"
                            + "used API version: " + data.getIntExtra(OpenPgpApi.EXTRA_API_VERSION, -1) + "\n"
                            + "supported API versions: 3, 4");
            result.putExtra(OpenPgpApi.RESULT_ERROR, error);
            result.putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR);
            return result;
        }

        // check if caller is allowed to access openpgp keychain
        Intent result = isAllowed(data);
        if (result != null) {
            return result;
        }

        return null;
    }

    private String getAccountName(Intent data) {
        String accName = data.getStringExtra(OpenPgpApi.EXTRA_ACCOUNT_NAME);
        // if no account name is given use name "default"
        if (TextUtils.isEmpty(accName)) {
            accName = "default";
        }
        Log.d(Constants.TAG, "accName: " + accName);
        return accName;
    }

    // TODO: multi-threading
    private final IOpenPgpService.Stub mBinder = new IOpenPgpService.Stub() {

        @Override
        public Intent execute(Intent data, ParcelFileDescriptor input, ParcelFileDescriptor output) {
            Intent errorResult = checkRequirements(data);
            if (errorResult != null) {
                return errorResult;
            }

            String accName = getAccountName(data);
            final AccountSettings accSettings = getAccSettings(accName);
            if (accSettings == null) {
                return getCreateAccountIntent(data, accName);
            }

            String action = data.getAction();
            if (OpenPgpApi.ACTION_SIGN.equals(action)) {
                return signImpl(data, input, output, accSettings);
            } else if (OpenPgpApi.ACTION_ENCRYPT.equals(action)) {
                return encryptAndSignImpl(data, input, output, accSettings, false);
            } else if (OpenPgpApi.ACTION_SIGN_AND_ENCRYPT.equals(action)) {
                return encryptAndSignImpl(data, input, output, accSettings, true);
            } else if (OpenPgpApi.ACTION_DECRYPT_VERIFY.equals(action)) {
                String currentPkg = getCurrentCallingPackage();
                Set<Long> allowedKeyIds =
                        mProviderHelper.getAllKeyIdsForApp(
                                ApiAccounts.buildBaseUri(currentPkg));
                return decryptAndVerifyImpl(data, input, output, allowedKeyIds, false);
            } else if (OpenPgpApi.ACTION_DECRYPT_METADATA.equals(action)) {
                String currentPkg = getCurrentCallingPackage();
                Set<Long> allowedKeyIds =
                        mProviderHelper.getAllKeyIdsForApp(
                                ApiAccounts.buildBaseUri(currentPkg));
                return decryptAndVerifyImpl(data, input, output, allowedKeyIds, true);
            } else if (OpenPgpApi.ACTION_GET_KEY.equals(action)) {
                return getKeyImpl(data);
            } else if (OpenPgpApi.ACTION_GET_KEY_IDS.equals(action)) {
                return getKeyIdsImpl(data);
            } else {
                return null;
            }
        }

    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

}
