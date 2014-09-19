/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.sufficientlysecure.keychain.pgp;

import org.spongycastle.bcpg.SignatureSubpacket;
import org.spongycastle.bcpg.SignatureSubpacketTags;
import org.spongycastle.bcpg.sig.Exportable;
import org.spongycastle.bcpg.sig.Revocable;
import org.spongycastle.bcpg.sig.RevocationReason;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPObjectFactory;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPSignature;
import org.spongycastle.openpgp.PGPSignatureList;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.util.Log;

import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Date;

/** OpenKeychain wrapper around PGPSignature objects.
 *
 * This is a mostly simple wrapper around a single bouncycastle PGPSignature
 * object. It exposes high level getters for all relevant information, methods
 * for verification of various signatures (uid binding, subkey binding, generic
 * bytes), and a static method for construction from bytes.
 *
 */
public class WrappedSignature {

    public static final int DEFAULT_CERTIFICATION = PGPSignature.DEFAULT_CERTIFICATION;
    public static final int NO_CERTIFICATION = PGPSignature.NO_CERTIFICATION;
    public static final int CASUAL_CERTIFICATION = PGPSignature.CASUAL_CERTIFICATION;
    public static final int POSITIVE_CERTIFICATION = PGPSignature.POSITIVE_CERTIFICATION;
    public static final int CERTIFICATION_REVOCATION = PGPSignature.CERTIFICATION_REVOCATION;

    final PGPSignature mSig;

    WrappedSignature(PGPSignature sig) {
        mSig = sig;
    }

    public long getKeyId() {
        return mSig.getKeyID();
    }

    public int getSignatureType() {
        return mSig.getSignatureType();
    }

    public int getKeyAlgorithm() {
        return mSig.getKeyAlgorithm();
    }

    public Date getCreationTime() {
        return mSig.getCreationTime();
    }

    public ArrayList<WrappedSignature> getEmbeddedSignatures() {
        ArrayList<WrappedSignature> sigs = new ArrayList<WrappedSignature>();
        if (!mSig.hasSubpackets()) {
            return sigs;
        }
        try {
            PGPSignatureList list;
            if (mSig.getHashedSubPackets() != null) {
                list = mSig.getHashedSubPackets().getEmbeddedSignatures();
                for (int i = 0; i < list.size(); i++) {
                    sigs.add(new WrappedSignature(list.get(i)));
                }
            }
            if (mSig.getUnhashedSubPackets() != null) {
                list = mSig.getUnhashedSubPackets().getEmbeddedSignatures();
                for (int i = 0; i < list.size(); i++) {
                    sigs.add(new WrappedSignature(list.get(i)));
                }
            }
        } catch (PGPException e) {
            // no matter
            Log.e(Constants.TAG, "exception reading embedded signatures", e);
        }
        return sigs;
    }

    public byte[] getEncoded() throws IOException {
        return mSig.getEncoded();
    }

    public boolean isRevocation() {
        return mSig.getSignatureType() == PGPSignature.CERTIFICATION_REVOCATION;
    }

    public boolean isPrimaryUserId() {
        return mSig.getHashedSubPackets().isPrimaryUserID();
    }

    public String getRevocationReason() throws PgpGeneralException {
        if(!isRevocation()) {
            throw new PgpGeneralException("Not a revocation signature.");
        }
        if (mSig.getHashedSubPackets() == null) {
            return null;
        }
        SignatureSubpacket p = mSig.getHashedSubPackets().getSubpacket(
                SignatureSubpacketTags.REVOCATION_REASON);
        // For some reason, this is missing in SignatureSubpacketInputStream:146
        if (!(p instanceof RevocationReason)) {
            p = new RevocationReason(false, p.getData());
        }
        return ((RevocationReason) p).getRevocationDescription();
    }

    public void init(CanonicalizedPublicKey key) throws PgpGeneralException {
        init(key.getPublicKey());
    }

    public void init(UncachedPublicKey key) throws PgpGeneralException {
        init(key.getPublicKey());
    }

    void init(PGPPublicKey key) throws PgpGeneralException {
        try {
            JcaPGPContentVerifierBuilderProvider contentVerifierBuilderProvider =
                    new JcaPGPContentVerifierBuilderProvider()
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
            mSig.init(contentVerifierBuilderProvider, key);
        } catch(PGPException e) {
            throw new PgpGeneralException(e);
        }
    }

    public void update(byte[] data, int offset, int length) {
        mSig.update(data, offset, length);
    }

    public void update(byte data) {
        mSig.update(data);
    }

    public boolean verify() throws PgpGeneralException {
        try {
            return mSig.verify();
        } catch(PGPException e) {
            throw new PgpGeneralException(e);
        }
    }

    boolean verifySignature(PGPPublicKey key) throws PgpGeneralException {
        try {
            return mSig.verifyCertification(key);
        } catch (PGPException e) {
            throw new PgpGeneralException("Error!", e);
        }
    }

    boolean verifySignature(PGPPublicKey masterKey, PGPPublicKey subKey) throws PgpGeneralException {
        try {
            return mSig.verifyCertification(masterKey, subKey);
        } catch (PGPException e) {
            throw new PgpGeneralException("Error!", e);
        }
    }

    boolean verifySignature(PGPPublicKey key, String uid) throws PgpGeneralException {
        try {
            return mSig.verifyCertification(uid, key);
        } catch (PGPException e) {
            throw new PgpGeneralException("Error!", e);
        }
    }

    boolean verifySignature(PGPPublicKey key, byte[] rawUserId) throws PgpGeneralException {
        try {
            return mSig.verifyCertification(rawUserId, key);
        } catch (PGPException e) {
            throw new PgpGeneralException("Error!", e);
        }
    }

    public boolean verifySignature(UncachedPublicKey key, byte[] rawUserId) throws PgpGeneralException {
        return verifySignature(key.getPublicKey(), rawUserId);
    }
    public boolean verifySignature(CanonicalizedPublicKey key, String uid) throws PgpGeneralException {
        return verifySignature(key.getPublicKey(), uid);
    }

    public static WrappedSignature fromBytes(byte[] data) {
        PGPObjectFactory factory = new PGPObjectFactory(data);
        PGPSignatureList signatures = null;
        try {
            if ((signatures = (PGPSignatureList) factory.nextObject()) == null || signatures.isEmpty()) {
                Log.e(Constants.TAG, "No signatures given!");
                return null;
            }
        } catch (IOException e) {
            Log.e(Constants.TAG, "Error while converting to PGPSignature!", e);
            return null;
        }

        return new WrappedSignature(signatures.get(0));
    }

    /** Returns true if this certificate is revocable in general. */
    public boolean isRevokable () {
        // If nothing is specified, the packet is considered revocable
        if (mSig.getHashedSubPackets() == null
                || !mSig.getHashedSubPackets().hasSubpacket(SignatureSubpacketTags.REVOCABLE)) {
            return true;
        }
        SignatureSubpacket p = mSig.getHashedSubPackets().getSubpacket(SignatureSubpacketTags.REVOCABLE);
        return ((Revocable) p).isRevocable();
    }

    public boolean isLocal() {
        if (mSig.getHashedSubPackets() == null
                || !mSig.getHashedSubPackets().hasSubpacket(SignatureSubpacketTags.EXPORTABLE)) {
            return false;
        }
        SignatureSubpacket p = mSig.getHashedSubPackets().getSubpacket(SignatureSubpacketTags.EXPORTABLE);
        return ! ((Exportable) p).isExportable();
    }
}
