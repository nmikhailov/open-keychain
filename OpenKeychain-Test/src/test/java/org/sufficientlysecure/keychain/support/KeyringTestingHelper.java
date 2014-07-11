/*
 * Copyright (C) Art O Cathain, Vincent Breitmoser
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
package org.sufficientlysecure.keychain.support;

import android.content.Context;

import org.spongycastle.util.Arrays;
import org.sufficientlysecure.keychain.pgp.NullProgressable;
import org.sufficientlysecure.keychain.pgp.UncachedKeyRing;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.OperationResults;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Helper for tests of the Keyring import in ProviderHelper.
 */
public class KeyringTestingHelper {

    private final Context context;

    public KeyringTestingHelper(Context robolectricContext) {
        this.context = robolectricContext;
    }

    public boolean addKeyring(Collection<String> blobFiles) throws Exception {

        ProviderHelper providerHelper = new ProviderHelper(context);

        byte[] data = TestDataUtil.readAllFully(blobFiles);
        UncachedKeyRing ring = UncachedKeyRing.decodeFromData(data);
        long masterKeyId = ring.getMasterKeyId();

        // Should throw an exception; key is not yet saved
        retrieveKeyAndExpectNotFound(providerHelper, masterKeyId);

        OperationResults.SaveKeyringResult saveKeyringResult = providerHelper.savePublicKeyRing(ring, new NullProgressable());

        boolean saveSuccess = saveKeyringResult.success();

        // Now re-retrieve the saved key. Should not throw an exception.
        providerHelper.getWrappedPublicKeyRing(masterKeyId);

        // A different ID should still fail
        retrieveKeyAndExpectNotFound(providerHelper, masterKeyId - 1);

        return saveSuccess;
    }

    public static class RawPacket {
        public int position;
        public int tag;
        public int length;
        public byte[] buf;

        public boolean equals(Object other) {
            return other instanceof RawPacket && Arrays.areEqual(this.buf, ((RawPacket) other).buf);
        }

        public int hashCode() {
            // System.out.println("tag: " + tag + ", code: " + Arrays.hashCode(buf));
            return Arrays.hashCode(buf);
        }
    }

    public static final Comparator<RawPacket> packetOrder = new Comparator<RawPacket>() {
        public int compare(RawPacket left, RawPacket right) {
            return Integer.compare(left.position, right.position);
        }
    };

    public static boolean diffKeyrings(byte[] ringA, byte[] ringB,
                                       List<RawPacket> onlyA, List<RawPacket> onlyB)
            throws IOException {
        InputStream streamA = new ByteArrayInputStream(ringA);
        InputStream streamB = new ByteArrayInputStream(ringB);

        HashSet<RawPacket> a = new HashSet<RawPacket>(), b = new HashSet<RawPacket>();

        RawPacket p;
        int pos = 0;
        while(true) {
            p = readPacket(streamA);
            if (p == null) {
                break;
            }
            p.position = pos++;
            a.add(p);
        }
        pos = 0;
        while(true) {
            p = readPacket(streamB);
            if (p == null) {
                break;
            }
            p.position = pos++;
            b.add(p);
        }

        onlyA.clear();
        onlyB.clear();

        onlyA.addAll(a);
        onlyA.removeAll(b);
        onlyB.addAll(b);
        onlyB.removeAll(a);

        Collections.sort(onlyA, packetOrder);
        Collections.sort(onlyB, packetOrder);

        return !onlyA.isEmpty() || !onlyB.isEmpty();
    }

    private static RawPacket readPacket(InputStream in) throws IOException {

        // save here. this is tag + length, max 6 bytes
        in.mark(6);

        int hdr = in.read();
        int headerLength = 1;

        if (hdr < 0) {
            return null;
        }

        if ((hdr & 0x80) == 0) {
            throw new IOException("invalid header encountered");
        }

        boolean newPacket = (hdr & 0x40) != 0;
        int tag = 0;
        int bodyLen = 0;

        if (newPacket) {
            tag = hdr & 0x3f;

            int l = in.read();
            headerLength += 1;

            if (l < 192) {
                bodyLen = l;
            } else if (l <= 223) {
                int b = in.read();
                headerLength += 1;

                bodyLen = ((l - 192) << 8) + (b) + 192;
            } else if (l == 255) {
                bodyLen = (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
                headerLength += 4;
            } else {
                // bodyLen = 1 << (l & 0x1f);
                throw new IOException("no support for partial bodies in test classes");
            }
        } else {
            int lengthType = hdr & 0x3;

            tag = (hdr & 0x3f) >> 2;

            switch (lengthType) {
                case 0:
                    bodyLen = in.read();
                    headerLength += 1;
                    break;
                case 1:
                    bodyLen = (in.read() << 8) | in.read();
                    headerLength += 2;
                    break;
                case 2:
                    bodyLen = (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
                    headerLength += 4;
                    break;
                case 3:
                    // bodyLen = 1 << (l & 0x1f);
                    throw new IOException("no support for partial bodies in test classes");
                default:
                    throw new IOException("unknown length type encountered");
            }
        }

        in.reset();

        // read the entire packet INCLUDING the header here
        byte[] buf = new byte[headerLength+bodyLen];
        if (in.read(buf) != headerLength+bodyLen) {
            throw new IOException("read length mismatch!");
        }
        RawPacket p = new RawPacket();
        p.tag = tag;
        p.length = bodyLen;
        p.buf = buf;
        return p;

    }

    private void retrieveKeyAndExpectNotFound(ProviderHelper providerHelper, long masterKeyId) {
        try {
            providerHelper.getWrappedPublicKeyRing(masterKeyId);
            throw new AssertionError("Was expecting the previous call to fail!");
        } catch (ProviderHelper.NotFoundException expectedException) {
            // good
        }
    }

}
