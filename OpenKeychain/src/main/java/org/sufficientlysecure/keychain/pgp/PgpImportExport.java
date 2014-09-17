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

package org.sufficientlysecure.keychain.pgp;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import org.spongycastle.bcpg.ArmoredOutputStream;
import org.spongycastle.openpgp.PGPException;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.keyimport.HkpKeyserver;
import org.sufficientlysecure.keychain.keyimport.Keyserver.AddKeyException;
import org.sufficientlysecure.keychain.keyimport.ParcelableKeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.results.OperationResult.LogType;
import org.sufficientlysecure.keychain.service.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.service.results.ImportKeyResult;
import org.sufficientlysecure.keychain.service.results.SaveKeyringResult;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.util.IterableIterator;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.ProgressScaler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class PgpImportExport {

    private Context mContext;
    private Progressable mProgressable;
    private AtomicBoolean mCancelled;

    private ProviderHelper mProviderHelper;

    public PgpImportExport(Context context, ProviderHelper providerHelper, Progressable progressable) {
        super();
        this.mContext = context;
        this.mProgressable = progressable;
        this.mProviderHelper = providerHelper;
    }

    public PgpImportExport(Context context, ProviderHelper providerHelper, Progressable progressable, AtomicBoolean cancelled) {
        super();
        mContext = context;
        mProgressable = progressable;
        mProviderHelper = providerHelper;
        mCancelled = cancelled;
    }

    public void updateProgress(int message, int current, int total) {
        if (mProgressable != null) {
            mProgressable.setProgress(message, current, total);
        }
    }

    public void updateProgress(String message, int current, int total) {
        if (mProgressable != null) {
            mProgressable.setProgress(message, current, total);
        }
    }

    public void updateProgress(int current, int total) {
        if (mProgressable != null) {
            mProgressable.setProgress(current, total);
        }
    }

    public void uploadKeyRingToServer(HkpKeyserver server, CanonicalizedPublicKeyRing keyring) throws AddKeyException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ArmoredOutputStream aos = null;
        try {
            aos = new ArmoredOutputStream(bos);
            keyring.encode(aos);
            aos.close();

            String armoredKey = bos.toString("UTF-8");
            server.add(armoredKey);
        } catch (IOException e) {
            Log.e(Constants.TAG, "IOException", e);
            throw new AddKeyException();
        } finally {
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

    /** Imports keys from given data. If keyIds is given only those are imported */
    public ImportKeyResult importKeyRings(List<ParcelableKeyRing> entries) {
        return importKeyRings(entries.iterator(), entries.size());
    }

    public ImportKeyResult importKeyRings(Iterator<ParcelableKeyRing> entries, int num) {
        updateProgress(R.string.progress_importing, 0, 100);

        // If there aren't even any keys, do nothing here.
        if (entries == null || !entries.hasNext()) {
            return new ImportKeyResult(
                    ImportKeyResult.RESULT_FAIL_NOTHING, mProviderHelper.getLog(), 0, 0, 0, 0);
        }

        int newKeys = 0, oldKeys = 0, badKeys = 0, secret = 0;

        int position = 0;
        double progSteps = 100.0 / num;
        for (ParcelableKeyRing entry : new IterableIterator<ParcelableKeyRing>(entries)) {
            // Has this action been cancelled? If so, don't proceed any further
            if (mCancelled != null && mCancelled.get()) {
                break;
            }

            try {
                UncachedKeyRing key = UncachedKeyRing.decodeFromData(entry.getBytes());

                String expectedFp = entry.getExpectedFingerprint();
                if(expectedFp != null) {
                    if(!KeyFormattingUtils.convertFingerprintToHex(key.getFingerprint()).equals(expectedFp)) {
                        Log.d(Constants.TAG, "fingerprint: " + KeyFormattingUtils.convertFingerprintToHex(key.getFingerprint()));
                        Log.d(Constants.TAG, "expected fingerprint: " + expectedFp);
                        Log.e(Constants.TAG, "Actual key fingerprint is not the same as expected!");
                        badKeys += 1;
                        continue;
                    } else {
                        Log.d(Constants.TAG, "Actual key fingerprint matches expected one.");
                    }
                }

                SaveKeyringResult result;
                if (key.isSecret()) {
                    result = mProviderHelper.saveSecretKeyRing(key,
                            new ProgressScaler(mProgressable, (int)(position*progSteps), (int)((position+1)*progSteps), 100));
                } else {
                    result = mProviderHelper.savePublicKeyRing(key,
                            new ProgressScaler(mProgressable, (int)(position*progSteps), (int)((position+1)*progSteps), 100));
                }
                if (!result.success()) {
                    badKeys += 1;
                } else if (result.updated()) {
                    oldKeys += 1;
                } else {
                    newKeys += 1;
                    if (key.isSecret()) {
                        secret += 1;
                    }
                }

            } catch (IOException e) {
                Log.e(Constants.TAG, "Encountered bad key on import!", e);
                ++badKeys;
            } catch (PgpGeneralException e) {
                Log.e(Constants.TAG, "Encountered bad key on import!", e);
                ++badKeys;
            }
            // update progress
            position++;
        }

        OperationLog log = mProviderHelper.getLog();
        int resultType = 0;
        // special return case: no new keys at all
        if (badKeys == 0 && newKeys == 0 && oldKeys == 0) {
            resultType = ImportKeyResult.RESULT_FAIL_NOTHING;
        } else {
            if (newKeys > 0) {
                resultType |= ImportKeyResult.RESULT_OK_NEWKEYS;
            }
            if (oldKeys > 0) {
                resultType |= ImportKeyResult.RESULT_OK_UPDATED;
            }
            if (badKeys > 0) {
                resultType |= ImportKeyResult.RESULT_WITH_ERRORS;
                if (newKeys == 0 && oldKeys == 0) {
                    resultType |= ImportKeyResult.RESULT_ERROR;
                }
            }
            if (log.containsWarnings()) {
                resultType |= ImportKeyResult.RESULT_WARNINGS;
            }
        }
        if (mCancelled != null && mCancelled.get()) {
            log.add(LogType.MSG_OPERATION_CANCELLED, 0);
            resultType |= ImportKeyResult.RESULT_CANCELLED;
        }

        return new ImportKeyResult(resultType, log, newKeys, oldKeys, badKeys, secret);

    }

    public Bundle exportKeyRings(ArrayList<Long> publicKeyRingMasterIds,
                                 ArrayList<Long> secretKeyRingMasterIds,
                                 OutputStream outStream) throws PgpGeneralException,
            PGPException, IOException {
        Bundle returnData = new Bundle();

        int masterKeyIdsSize = publicKeyRingMasterIds.size() + secretKeyRingMasterIds.size();
        int progress = 0;

        updateProgress(
                mContext.getResources().getQuantityString(R.plurals.progress_exporting_key,
                        masterKeyIdsSize), 0, 100);

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            throw new PgpGeneralException(
                    mContext.getString(R.string.error_external_storage_not_ready));
        }
        // For each public masterKey id
        for (long pubKeyMasterId : publicKeyRingMasterIds) {
            progress++;
            // Create an output stream
            ArmoredOutputStream arOutStream = new ArmoredOutputStream(outStream);
            String version = PgpHelper.getVersionForHeader(mContext);
            if (version != null) {
                arOutStream.setHeader("Version", version);
            }

            updateProgress(progress * 100 / masterKeyIdsSize, 100);

            try {
                CanonicalizedPublicKeyRing ring = mProviderHelper.getCanonicalizedPublicKeyRing(
                        KeychainContract.KeyRings.buildUnifiedKeyRingUri(pubKeyMasterId)
                );

                ring.encode(arOutStream);
            } catch (ProviderHelper.NotFoundException e) {
                Log.e(Constants.TAG, "key not found!", e);
                // TODO: inform user?
            }

            arOutStream.close();
        }

        // For each secret masterKey id
        for (long secretKeyMasterId : secretKeyRingMasterIds) {
            progress++;
            // Create an output stream
            ArmoredOutputStream arOutStream = new ArmoredOutputStream(outStream);
            String version = PgpHelper.getVersionForHeader(mContext);
            if (version != null) {
                arOutStream.setHeader("Version", version);
            }

            updateProgress(progress * 100 / masterKeyIdsSize, 100);

            try {
                CanonicalizedSecretKeyRing secretKeyRing =
                        mProviderHelper.getCanonicalizedSecretKeyRing(secretKeyMasterId);
                secretKeyRing.encode(arOutStream);
            } catch (ProviderHelper.NotFoundException e) {
                Log.e(Constants.TAG, "key not found!", e);
                // TODO: inform user?
            }

            arOutStream.close();
        }

        returnData.putInt(KeychainIntentService.RESULT_EXPORT, masterKeyIdsSize);

        updateProgress(R.string.progress_done, 100, 100);

        return returnData;
    }

}
