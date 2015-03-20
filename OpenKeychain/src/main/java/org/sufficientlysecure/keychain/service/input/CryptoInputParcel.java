package org.sufficientlysecure.keychain.service.input;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import android.os.Parcel;
import android.os.Parcelable;

import org.sufficientlysecure.keychain.util.Passphrase;


/** This is a base class for the input of crypto operations.
 *
 */
public class CryptoInputParcel implements Parcelable {

    final Date mSignatureTime;
    final Passphrase mPassphrase;

    // this map contains both decrypted session keys and signed hashes to be
    // used in the crypto operation described by this parcel.
    private HashMap<ByteBuffer,byte[]> mCryptoData = new HashMap<>();

    public CryptoInputParcel() {
        mSignatureTime = new Date();
        mPassphrase = null;
    }

    public CryptoInputParcel(Date signatureTime, Passphrase passphrase) {
        mSignatureTime = signatureTime == null ? new Date() : signatureTime;
        mPassphrase = passphrase;
    }

    public CryptoInputParcel(Passphrase passphrase) {
        mSignatureTime = new Date();
        mPassphrase = passphrase;
    }

    public CryptoInputParcel(Date signatureTime) {
        mSignatureTime = signatureTime == null ? new Date() : signatureTime;
        mPassphrase = null;
    }

    protected CryptoInputParcel(Parcel source) {
        mSignatureTime = new Date(source.readLong());
        mPassphrase = source.readParcelable(getClass().getClassLoader());

        {
            int count = source.readInt();
            mCryptoData = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                byte[] key = source.createByteArray();
                byte[] value = source.createByteArray();
                mCryptoData.put(ByteBuffer.wrap(key), value);
            }
        }

    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mSignatureTime.getTime());
        dest.writeParcelable(mPassphrase, 0);

        dest.writeInt(mCryptoData.size());
        for (HashMap.Entry<ByteBuffer,byte[]> entry : mCryptoData.entrySet()) {
            dest.writeByteArray(entry.getKey().array());
            dest.writeByteArray(entry.getValue());
        }
    }

    public void addCryptoData(byte[] hash, byte[] signedHash) {
        mCryptoData.put(ByteBuffer.wrap(hash), signedHash);
    }

    public Map<ByteBuffer, byte[]> getCryptoData() {
        return Collections.unmodifiableMap(mCryptoData);
    }

    public Date getSignatureTime() {
        return mSignatureTime;
    }

    public boolean hasPassphrase() {
        return mPassphrase != null;
    }

    public Passphrase getPassphrase() {
        return mPassphrase;
    }

    public static final Creator<CryptoInputParcel> CREATOR = new Creator<CryptoInputParcel>() {
        public CryptoInputParcel createFromParcel(final Parcel source) {
            return new CryptoInputParcel(source);
        }

        public CryptoInputParcel[] newArray(final int size) {
            return new CryptoInputParcel[size];
        }
    };

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("CryptoInput: { ");
        b.append(mSignatureTime).append(" ");
        if (mPassphrase != null) {
            b.append("passphrase");
        }
        if (mCryptoData != null) {
            b.append(mCryptoData.size());
            b.append(" hashes ");
        }
        b.append("}");
        return b.toString();
    }
}
