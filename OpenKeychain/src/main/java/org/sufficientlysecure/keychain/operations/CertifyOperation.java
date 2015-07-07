/*
 * Copyright (C) 2014-2015 Vincent Breitmoser <v.breitmoser@mugenguild.com>
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

import android.content.Context;
import android.support.annotation.NonNull;

import org.sufficientlysecure.keychain.keyimport.HkpKeyserver;
import org.sufficientlysecure.keychain.operations.results.CertifyResult;
import org.sufficientlysecure.keychain.operations.results.ExportResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult.LogType;
import org.sufficientlysecure.keychain.operations.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.operations.results.SaveKeyringResult;
import org.sufficientlysecure.keychain.pgp.CanonicalizedPublicKeyRing;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKeyRing;
import org.sufficientlysecure.keychain.pgp.PassphraseCacheInterface;
import org.sufficientlysecure.keychain.pgp.PgpCertifyOperation;
import org.sufficientlysecure.keychain.pgp.PgpCertifyOperation.PgpCertifyResult;
import org.sufficientlysecure.keychain.pgp.Progressable;
import org.sufficientlysecure.keychain.pgp.UncachedKeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.provider.ProviderHelper.NotFoundException;
import org.sufficientlysecure.keychain.service.CertifyActionsParcel;
import org.sufficientlysecure.keychain.service.CertifyActionsParcel.CertifyAction;
import org.sufficientlysecure.keychain.service.ContactSyncAdapterService;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel.NfcSignOperationsBuilder;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.util.Passphrase;
import org.sufficientlysecure.keychain.util.Preferences;
import org.sufficientlysecure.keychain.util.orbot.OrbotHelper;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An operation which implements a high level user id certification operation.
 * <p/>
 * This operation takes a specific CertifyActionsParcel as its input. These
 * contain a masterKeyId to be used for certification, and a list of
 * masterKeyIds and related user ids to certify.
 *
 * @see CertifyActionsParcel
 */
public class CertifyOperation extends BaseOperation<CertifyActionsParcel> {

    public CertifyOperation(Context context, ProviderHelper providerHelper, Progressable progressable, AtomicBoolean
            cancelled) {
        super(context, providerHelper, progressable, cancelled);
    }

    @NonNull
    @Override
    public CertifyResult execute(CertifyActionsParcel parcel, CryptoInputParcel cryptoInput) {

        OperationLog log = new OperationLog();
        log.add(LogType.MSG_CRT, 0);

        // Retrieve and unlock secret key
        CanonicalizedSecretKey certificationKey;
        try {

            log.add(LogType.MSG_CRT_MASTER_FETCH, 1);
            CanonicalizedSecretKeyRing secretKeyRing =
                    mProviderHelper.getCanonicalizedSecretKeyRing(parcel.mMasterKeyId);
            log.add(LogType.MSG_CRT_UNLOCK, 1);
            certificationKey = secretKeyRing.getSecretKey();

            Passphrase passphrase;

            switch (certificationKey.getSecretKeyType()) {
                case PIN:
                case PATTERN:
                case PASSPHRASE:
                    passphrase = cryptoInput.getPassphrase();
                    if (passphrase == null) {
                        try {
                            passphrase = getCachedPassphrase(certificationKey.getKeyId(), certificationKey.getKeyId());
                        } catch (PassphraseCacheInterface.NoSecretKeyException ignored) {
                            // treat as a cache miss for error handling purposes
                        }
                    }

                    if (passphrase == null) {
                        return new CertifyResult(log,
                                RequiredInputParcel.createRequiredSignPassphrase(
                                certificationKey.getKeyId(), certificationKey.getKeyId(), null)
                        );
                    }
                    break;

                case PASSPHRASE_EMPTY:
                    passphrase = new Passphrase("");
                    break;

                case DIVERT_TO_CARD:
                    // the unlock operation will succeed for passphrase == null in a divertToCard key
                    passphrase = null;
                    break;

                default:
                    log.add(LogType.MSG_CRT_ERROR_UNLOCK, 2);
                    return new CertifyResult(CertifyResult.RESULT_ERROR, log);
            }

            if (!certificationKey.unlock(passphrase)) {
                log.add(LogType.MSG_CRT_ERROR_UNLOCK, 2);
                return new CertifyResult(CertifyResult.RESULT_ERROR, log);
            }
        } catch (PgpGeneralException e) {
            log.add(LogType.MSG_CRT_ERROR_UNLOCK, 2);
            return new CertifyResult(CertifyResult.RESULT_ERROR, log);
        } catch (NotFoundException e) {
            log.add(LogType.MSG_CRT_ERROR_MASTER_NOT_FOUND, 2);
            return new CertifyResult(CertifyResult.RESULT_ERROR, log);
        }

        ArrayList<UncachedKeyRing> certifiedKeys = new ArrayList<>();

        log.add(LogType.MSG_CRT_CERTIFYING, 1);

        int certifyOk = 0, certifyError = 0, uploadOk = 0, uploadError = 0;

        NfcSignOperationsBuilder allRequiredInput = new NfcSignOperationsBuilder(
                cryptoInput.getSignatureTime(), certificationKey.getKeyId(),
                certificationKey.getKeyId());

        // Work through all requested certifications
        for (CertifyAction action : parcel.mCertifyActions) {

            // Check if we were cancelled
            if (checkCancelled()) {
                log.add(LogType.MSG_OPERATION_CANCELLED, 0);
                return new CertifyResult(CertifyResult.RESULT_CANCELLED, log);
            }

            try {

                if (action.mMasterKeyId == parcel.mMasterKeyId) {
                    log.add(LogType.MSG_CRT_ERROR_SELF, 2);
                    certifyError += 1;
                    continue;
                }

                CanonicalizedPublicKeyRing publicRing =
                        mProviderHelper.getCanonicalizedPublicKeyRing(action.mMasterKeyId);

                PgpCertifyOperation op = new PgpCertifyOperation();
                PgpCertifyResult result = op.certify(certificationKey, publicRing,
                        log, 2, action, cryptoInput.getCryptoData(), cryptoInput.getSignatureTime());

                if (!result.success()) {
                    certifyError += 1;
                    continue;
                }
                if (result.nfcInputRequired()) {
                    RequiredInputParcel requiredInput = result.getRequiredInput();
                    allRequiredInput.addAll(requiredInput);
                    continue;
                }

                certifiedKeys.add(result.getCertifiedRing());

            } catch (NotFoundException e) {
                certifyError += 1;
                log.add(LogType.MSG_CRT_WARN_NOT_FOUND, 3);
            }

        }

        if (!allRequiredInput.isEmpty()) {
            log.add(LogType.MSG_CRT_NFC_RETURN, 1);
            return new CertifyResult(log, allRequiredInput.build());
        }

        log.add(LogType.MSG_CRT_SAVING, 1);

        // Check if we were cancelled
        if (checkCancelled()) {
            log.add(LogType.MSG_OPERATION_CANCELLED, 0);
            return new CertifyResult(CertifyResult.RESULT_CANCELLED, log);
        }

        // these variables are used inside the following loop, but they need to be created only once
        HkpKeyserver keyServer = null;
        ExportOperation exportOperation = null;
        Proxy proxy = null;
        if (parcel.keyServerUri != null) {
            keyServer = new HkpKeyserver(parcel.keyServerUri);
            exportOperation = new ExportOperation(mContext, mProviderHelper, mProgressable);
            if (cryptoInput.getParcelableProxy() == null) {
                // explicit proxy not set
                if (!OrbotHelper.isOrbotInRequiredState(mContext)) {
                    return new CertifyResult(null,
                            RequiredInputParcel.createOrbotRequiredOperation());
                }
                proxy = Preferences.getPreferences(mContext).getProxyPrefs()
                        .parcelableProxy.getProxy();
            } else {
                proxy = cryptoInput.getParcelableProxy().getProxy();
            }
        }

        // Write all certified keys into the database
        for (UncachedKeyRing certifiedKey : certifiedKeys) {

            // Check if we were cancelled
            if (checkCancelled()) {
                log.add(LogType.MSG_OPERATION_CANCELLED, 0);
                return new CertifyResult(CertifyResult.RESULT_CANCELLED, log, certifyOk, certifyError, uploadOk,
                        uploadError);
            }

            log.add(LogType.MSG_CRT_SAVE, 2,
                    KeyFormattingUtils.convertKeyIdToHex(certifiedKey.getMasterKeyId()));
            // store the signed key in our local cache
            mProviderHelper.clearLog();
            SaveKeyringResult result = mProviderHelper.savePublicKeyRing(certifiedKey);

            if (exportOperation != null) {
                ExportResult uploadResult = exportOperation.uploadKeyRingToServer(
                        keyServer,
                        certifiedKey,
                        proxy);
                log.add(uploadResult, 2);

                if (uploadResult.success()) {
                    uploadOk += 1;
                } else {
                    uploadError += 1;
                }
            }

            if (result.success()) {
                certifyOk += 1;
            } else {
                log.add(LogType.MSG_CRT_WARN_SAVE_FAILED, 3);
            }

            log.add(result, 2);
        }

        if (certifyOk == 0) {
            log.add(LogType.MSG_CRT_ERROR_NOTHING, 0);
            return new CertifyResult(CertifyResult.RESULT_ERROR, log, certifyOk, certifyError,
                    uploadOk, uploadError);
        }

        // since only verified keys are synced to contacts, we need to initiate a sync now
        ContactSyncAdapterService.requestSync();

        log.add(LogType.MSG_CRT_SUCCESS, 0);
        if (uploadError != 0) {
            return new CertifyResult(CertifyResult.RESULT_WARNINGS, log, certifyOk, certifyError, uploadOk,
                    uploadError);
        } else {
            return new CertifyResult(CertifyResult.RESULT_OK, log, certifyOk, certifyError, uploadOk, uploadError);
        }

    }

}
