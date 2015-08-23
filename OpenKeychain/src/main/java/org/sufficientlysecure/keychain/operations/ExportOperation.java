/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010-2014 Thialfihar <thi@thialfihar.org>
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

package org.sufficientlysecure.keychain.operations;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import org.spongycastle.bcpg.ArmoredOutputStream;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.keyimport.HkpKeyserver;
import org.sufficientlysecure.keychain.keyimport.Keyserver.AddKeyException;
import org.sufficientlysecure.keychain.operations.results.ExportResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult.LogType;
import org.sufficientlysecure.keychain.operations.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.pgp.CanonicalizedKeyRing;
import org.sufficientlysecure.keychain.pgp.CanonicalizedPublicKeyRing;
import org.sufficientlysecure.keychain.pgp.Progressable;
import org.sufficientlysecure.keychain.pgp.UncachedKeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.KeychainDatabase.Tables;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.ExportKeyringParcel;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.util.FileHelper;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Preferences;
import org.sufficientlysecure.keychain.util.orbot.OrbotHelper;

/**
 * An operation class which implements high level export
 * operations.
 * This class receives a source and/or destination of keys as input and performs
 * all steps for this export.
 *
 * @see org.sufficientlysecure.keychain.ui.adapter.ImportKeysAdapter#getSelectedEntries()
 * For the export operation, the input consists of a set of key ids and
 * either the name of a file or an output uri to write to.
 */
public class ExportOperation extends BaseOperation<ExportKeyringParcel> {

    public ExportOperation(Context context, ProviderHelper providerHelper, Progressable
            progressable) {
        super(context, providerHelper, progressable);
    }

    public ExportOperation(Context context, ProviderHelper providerHelper,
                           Progressable progressable, AtomicBoolean cancelled) {
        super(context, providerHelper, progressable, cancelled);
    }

    public ExportResult uploadKeyRingToServer(HkpKeyserver server, CanonicalizedPublicKeyRing keyring,
                                      Proxy proxy) {
        return uploadKeyRingToServer(server, keyring.getUncachedKeyRing(), proxy);
    }

    public ExportResult uploadKeyRingToServer(HkpKeyserver server, UncachedKeyRing keyring, Proxy proxy) {
        mProgressable.setProgress(R.string.progress_uploading, 0, 1);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ArmoredOutputStream aos = null;
        OperationLog log = new OperationLog();
        log.add(LogType.MSG_EXPORT_UPLOAD_PUBLIC, 0, KeyFormattingUtils.convertKeyIdToHex(
                keyring.getPublicKey().getKeyId()
        ));

        try {
            aos = new ArmoredOutputStream(bos);
            keyring.encode(aos);
            aos.close();

            String armoredKey = bos.toString("UTF-8");
            server.add(armoredKey, proxy);

            log.add(LogType.MSG_EXPORT_UPLOAD_SUCCESS, 1);
            return new ExportResult(ExportResult.RESULT_OK, log);
        } catch (IOException e) {
            Log.e(Constants.TAG, "IOException", e);

            log.add(LogType.MSG_EXPORT_ERROR_KEY, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        } catch (AddKeyException e) {
            Log.e(Constants.TAG, "AddKeyException", e);

            log.add(LogType.MSG_EXPORT_ERROR_UPLOAD, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        } finally {
            mProgressable.setProgress(R.string.progress_uploading, 1, 1);
            try {
                if (aos != null) {
                    aos.close();
                }
                bos.close();
            } catch (IOException e) {
                // this is just a finally thing, no matter if it doesn't work out.
            }
        }
    }

    public ExportResult exportToFile(long[] masterKeyIds, boolean exportSecret, String outputFile) {

        OperationLog log = new OperationLog();
        if (masterKeyIds != null) {
            log.add(LogType.MSG_EXPORT, 0, masterKeyIds.length);
        } else {
            log.add(LogType.MSG_EXPORT_ALL, 0);
        }

        // do we have a file name?
        if (outputFile == null) {
            log.add(LogType.MSG_EXPORT_ERROR_NO_FILE, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        }

        log.add(LogType.MSG_EXPORT_FILE_NAME, 1, outputFile);

        // check if storage is ready
        if (!FileHelper.isStorageMounted(outputFile)) {
            log.add(LogType.MSG_EXPORT_ERROR_STORAGE, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        }

        try {
            OutputStream outStream = new FileOutputStream(outputFile);
            try {
                ExportResult result = exportKeyRings(log, masterKeyIds, exportSecret, outStream);
                if (result.cancelled()) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(outputFile).delete();
                }
                return result;
            } finally {
                outStream.close();
            }
        } catch (IOException e) {
            log.add(LogType.MSG_EXPORT_ERROR_FOPEN, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        }

    }

    public ExportResult exportToUri(long[] masterKeyIds, boolean exportSecret, Uri outputUri) {

        OperationLog log = new OperationLog();
        if (masterKeyIds != null) {
            log.add(LogType.MSG_EXPORT, 0, masterKeyIds.length);
        } else {
            log.add(LogType.MSG_EXPORT_ALL, 0);
        }

        // do we have a file name?
        if (outputUri == null) {
            log.add(LogType.MSG_EXPORT_ERROR_NO_URI, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        }

        try {
            OutputStream outStream = mProviderHelper.getContentResolver().openOutputStream
                    (outputUri);
            return exportKeyRings(log, masterKeyIds, exportSecret, outStream);
        } catch (FileNotFoundException e) {
            log.add(LogType.MSG_EXPORT_ERROR_URI_OPEN, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        }

    }

    ExportResult exportKeyRings(OperationLog log, long[] masterKeyIds, boolean exportSecret,
                                OutputStream outStream) {

        /* TODO isn't this checked above, with the isStorageMounted call?
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            log.add(LogType.MSG_EXPORT_ERROR_STORAGE, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log);
        }
        */

        if (!BufferedOutputStream.class.isInstance(outStream)) {
            outStream = new BufferedOutputStream(outStream);
        }

        int okSecret = 0, okPublic = 0, progress = 0;

        Cursor cursor = null;
        try {

            String selection = null, selectionArgs[] = null;

            if (masterKeyIds != null) {
                // convert long[] to String[]
                selectionArgs = new String[masterKeyIds.length];
                for (int i = 0; i < masterKeyIds.length; i++) {
                    selectionArgs[i] = Long.toString(masterKeyIds[i]);
                }

                // generates ?,?,? as placeholders for selectionArgs
                String placeholders = TextUtils.join(",",
                        Collections.nCopies(masterKeyIds.length, "?"));

                // put together selection string
                selection = Tables.KEYS + "." + KeyRings.MASTER_KEY_ID
                        + " IN (" + placeholders + ")";
            }

            cursor = mProviderHelper.getContentResolver().query(
                    KeyRings.buildUnifiedKeyRingsUri(), new String[]{
                            KeyRings.MASTER_KEY_ID, KeyRings.PUBKEY_DATA,
                            KeyRings.PRIVKEY_DATA, KeyRings.HAS_ANY_SECRET
                    }, selection, selectionArgs, Tables.KEYS + "." + KeyRings.MASTER_KEY_ID
            );

            if (cursor == null || !cursor.moveToFirst()) {
                log.add(LogType.MSG_EXPORT_ERROR_DB, 1);
                return new ExportResult(ExportResult.RESULT_ERROR, log, okPublic, okSecret);
            }

            int numKeys = cursor.getCount();

            updateProgress(
                    mContext.getResources().getQuantityString(R.plurals.progress_exporting_key,
                            numKeys), 0, numKeys);

            // For each public masterKey id
            while (!cursor.isAfterLast()) {

                long keyId = cursor.getLong(0);
                ArmoredOutputStream arOutStream = null;

                // Create an output stream
                try {
                    arOutStream = new ArmoredOutputStream(outStream);

                    log.add(LogType.MSG_EXPORT_PUBLIC, 1, KeyFormattingUtils.beautifyKeyId(keyId));

                    byte[] data = cursor.getBlob(1);
                    CanonicalizedKeyRing ring =
                            UncachedKeyRing.decodeFromData(data).canonicalize(log, 2, true);
                    ring.encode(arOutStream);

                    okPublic += 1;
                } catch (PgpGeneralException e) {
                    log.add(LogType.MSG_EXPORT_ERROR_KEY, 2);
                    updateProgress(progress++, numKeys);
                    continue;
                } finally {
                    // make sure this is closed
                    if (arOutStream != null) {
                        arOutStream.close();
                    }
                    arOutStream = null;
                }

                if (exportSecret && cursor.getInt(3) > 0) {
                    try {
                        arOutStream = new ArmoredOutputStream(outStream);

                        // export secret key part
                        log.add(LogType.MSG_EXPORT_SECRET, 2, KeyFormattingUtils.beautifyKeyId
                                (keyId));
                        byte[] data = cursor.getBlob(2);
                        CanonicalizedKeyRing ring =
                                UncachedKeyRing.decodeFromData(data).canonicalize(log, 2, true);
                        ring.encode(arOutStream);

                        okSecret += 1;
                    } catch (PgpGeneralException e) {
                        log.add(LogType.MSG_EXPORT_ERROR_KEY, 2);
                        updateProgress(progress++, numKeys);
                        continue;
                    } finally {
                        // make sure this is closed
                        if (arOutStream != null) {
                            arOutStream.close();
                        }
                    }
                }

                updateProgress(progress++, numKeys);

                cursor.moveToNext();
            }

            updateProgress(R.string.progress_done, numKeys, numKeys);

        } catch (IOException e) {
            log.add(LogType.MSG_EXPORT_ERROR_IO, 1);
            return new ExportResult(ExportResult.RESULT_ERROR, log, okPublic, okSecret);
        } finally {
            // Make sure the stream is closed
            if (outStream != null) try {
                outStream.close();
            } catch (Exception e) {
                Log.e(Constants.TAG, "error closing stream", e);
            }
            if (cursor != null) {
                cursor.close();
            }
        }


        log.add(LogType.MSG_EXPORT_SUCCESS, 1);
        return new ExportResult(ExportResult.RESULT_OK, log, okPublic, okSecret);

    }

    @NonNull
    public ExportResult execute(ExportKeyringParcel exportInput, CryptoInputParcel cryptoInput) {
        switch (exportInput.mExportType) {
            case UPLOAD_KEYSERVER: {
                Proxy proxy;
                if (cryptoInput.getParcelableProxy() == null) {
                    // explicit proxy not set
                    if (!OrbotHelper.isOrbotInRequiredState(mContext)) {
                        return new ExportResult(null,
                                RequiredInputParcel.createOrbotRequiredOperation(), cryptoInput);
                    }
                    proxy = Preferences.getPreferences(mContext).getProxyPrefs()
                            .parcelableProxy.getProxy();
                } else {
                    proxy = cryptoInput.getParcelableProxy().getProxy();
                }

                HkpKeyserver hkpKeyserver = new HkpKeyserver(exportInput.mKeyserver);
                try {
                    if (exportInput.mCanonicalizedPublicKeyringUri != null) {
                        CanonicalizedPublicKeyRing keyring
                                = mProviderHelper.getCanonicalizedPublicKeyRing(
                                exportInput.mCanonicalizedPublicKeyringUri);
                        return uploadKeyRingToServer(hkpKeyserver, keyring, proxy);
                    } else {
                        return uploadKeyRingToServer(hkpKeyserver, exportInput.mUncachedKeyRing,
                                proxy);
                    }
                } catch (ProviderHelper.NotFoundException e) {
                    Log.e(Constants.TAG, "error uploading key", e);
                    return new ExportResult(ExportResult.RESULT_ERROR, new OperationLog());
                }
            }
            case EXPORT_FILE: {
                return exportToFile(exportInput.mMasterKeyIds, exportInput.mExportSecret,
                        exportInput.mOutputFile);
            }
            case EXPORT_URI: {
                return exportToUri(exportInput.mMasterKeyIds, exportInput.mExportSecret,
                        exportInput.mOutputUri);
            }
            default: { // can never happen, all enum types must be handled above
                throw new AssertionError("must not happen, this is a bug!");
            }
        }
    }
}