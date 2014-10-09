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

package org.sufficientlysecure.keychain.pgp;

import org.openintents.openpgp.OpenPgpSignatureResult;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.util.Log;

import java.util.ArrayList;

/**
 * This class can be used to build OpenPgpSignatureResult objects based on several checks.
 * It serves as a constraint which information are returned inside an OpenPgpSignatureResult object.
 */
public class OpenPgpSignatureResultBuilder {
    // OpenPgpSignatureResult
    private boolean mSignatureOnly = false;
    private String mPrimaryUserId;
    private ArrayList<String> mUserIds = new ArrayList<String>();
    private long mKeyId;

    // builder
    private boolean mSignatureAvailable = false;
    private boolean mKnownKey = false;
    private boolean mValidSignature = false;
    private boolean mIsSignatureKeyCertified = false;
    private boolean mIsKeyRevoked = false;
    private boolean mIsKeyExpired = false;

    public void setSignatureOnly(boolean signatureOnly) {
        this.mSignatureOnly = signatureOnly;
    }

    public void setPrimaryUserId(String userId) {
        this.mPrimaryUserId = userId;
    }

    public void setKeyId(long keyId) {
        this.mKeyId = keyId;
    }

    public void setKnownKey(boolean knownKey) {
        this.mKnownKey = knownKey;
    }

    public void setValidSignature(boolean validSignature) {
        this.mValidSignature = validSignature;
    }

    public void setSignatureKeyCertified(boolean isSignatureKeyCertified) {
        this.mIsSignatureKeyCertified = isSignatureKeyCertified;
    }

    public void setSignatureAvailable(boolean signatureAvailable) {
        this.mSignatureAvailable = signatureAvailable;
    }

    public void setKeyRevoked(boolean keyRevoked) {
        this.mIsKeyRevoked = keyRevoked;
    }

    public void setKeyExpired(boolean keyExpired) {
        this.mIsKeyExpired = keyExpired;
    }

    public void setUserIds(ArrayList<String> userIds) {
        this.mUserIds = userIds;
    }

    public boolean isValidSignature() {
        return mValidSignature;
    }

    public void initValid(CanonicalizedPublicKeyRing signingRing,
                          CanonicalizedPublicKey signingKey) {
        setSignatureAvailable(true);
        setKnownKey(true);

        // from RING
        setKeyId(signingRing.getMasterKeyId());
        try {
            setPrimaryUserId(signingRing.getPrimaryUserIdWithFallback());
        } catch (PgpKeyNotFoundException e) {
            Log.d(Constants.TAG, "No primary user id in keyring with master key id " + signingRing.getMasterKeyId());
        }
        setSignatureKeyCertified(signingRing.getVerified() > 0);
        Log.d(Constants.TAG, "signingRing.getUnorderedUserIds(): " + signingRing.getUnorderedUserIds());
        setUserIds(signingRing.getUnorderedUserIds());

        // either master key is expired/revoked or this specific subkey is expired/revoked
        setKeyExpired(signingRing.isExpired() || signingKey.isExpired());
        setKeyRevoked(signingRing.isRevoked() || signingKey.isRevoked());
    }

    public OpenPgpSignatureResult build() {
        if (mSignatureAvailable) {
            OpenPgpSignatureResult result = new OpenPgpSignatureResult();
            result.setSignatureOnly(mSignatureOnly);

            // valid sig!
            if (mKnownKey) {
                if (mValidSignature) {
                    result.setKeyId(mKeyId);
                    result.setPrimaryUserId(mPrimaryUserId);
                    result.setUserIds(mUserIds);

                    if (mIsKeyRevoked) {
                        Log.d(Constants.TAG, "SIGNATURE_KEY_REVOKED");
                        result.setStatus(OpenPgpSignatureResult.SIGNATURE_KEY_REVOKED);
                    } else if (mIsKeyExpired) {
                        Log.d(Constants.TAG, "SIGNATURE_KEY_EXPIRED");
                        result.setStatus(OpenPgpSignatureResult.SIGNATURE_KEY_EXPIRED);
                    } else if (mIsSignatureKeyCertified) {
                        Log.d(Constants.TAG, "SIGNATURE_SUCCESS_CERTIFIED");
                        result.setStatus(OpenPgpSignatureResult.SIGNATURE_SUCCESS_CERTIFIED);
                    } else {
                        Log.d(Constants.TAG, "SIGNATURE_SUCCESS_UNCERTIFIED");
                        result.setStatus(OpenPgpSignatureResult.SIGNATURE_SUCCESS_UNCERTIFIED);
                    }
                } else {
                    Log.d(Constants.TAG, "Error! Invalid signature.");
                    result.setStatus(OpenPgpSignatureResult.SIGNATURE_ERROR);
                }
            } else {
                result.setKeyId(mKeyId);

                Log.d(Constants.TAG, "SIGNATURE_KEY_MISSING");
                result.setStatus(OpenPgpSignatureResult.SIGNATURE_KEY_MISSING);
            }

            return result;
        } else {
            Log.d(Constants.TAG, "no signature found!");

            return null;
        }
    }


}
