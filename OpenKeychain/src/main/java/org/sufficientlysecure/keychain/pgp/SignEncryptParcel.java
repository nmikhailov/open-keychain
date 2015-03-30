/*
 * Copyright (C) 2015 Dominik Schürmann <dominik@dominikschuermann.de>
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

import android.net.Uri;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** This parcel stores the input of one or more PgpSignEncrypt operations.
 * All operations will use the same general paramters, differing only in
 * input and output. Each input/output set depends on the paramters:
 *
 * - Each input uri is individually encrypted/signed
 * - If a byte array is supplied, it is treated as an input before uris are processed
 * - The number of output uris must match the number of input uris, plus one more
 *   if there is a byte array present.
 * - Once the output uris are empty, there must be exactly one input (uri xor bytes)
 *   left, which will be returned in a byte array as part of the result parcel.
 *
 */
public class SignEncryptParcel extends PgpSignEncryptInputParcel {

    public ArrayList<Uri> mInputUris = new ArrayList<>();
    public ArrayList<Uri> mOutputUris = new ArrayList<>();
    public byte[] mBytes;

    public SignEncryptParcel() {
        super();
    }

    public SignEncryptParcel(Parcel src) {
        super(src);

        mInputUris = src.createTypedArrayList(Uri.CREATOR);
        mOutputUris = src.createTypedArrayList(Uri.CREATOR);
        mBytes = src.createByteArray();

    }

    public byte[] getBytes() {
        return mBytes;
    }

    public void setBytes(byte[] bytes) {
        mBytes = bytes;
    }

    public List<Uri> getInputUris() {
        return Collections.unmodifiableList(mInputUris);
    }

    public void addInputUris(Collection<Uri> inputUris) {
        mInputUris.addAll(inputUris);
    }

    public List<Uri> getOutputUris() {
        return Collections.unmodifiableList(mOutputUris);
    }

    public void addOutputUris(ArrayList<Uri> outputUris) {
        mOutputUris.addAll(outputUris);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);

        dest.writeTypedList(mInputUris);
        dest.writeTypedList(mOutputUris);
        dest.writeByteArray(mBytes);
    }

    public static final Creator<SignEncryptParcel> CREATOR = new Creator<SignEncryptParcel>() {
        public SignEncryptParcel createFromParcel(final Parcel source) {
            return new SignEncryptParcel(source);
        }

        public SignEncryptParcel[] newArray(final int size) {
            return new SignEncryptParcel[size];
        }
    };

}
