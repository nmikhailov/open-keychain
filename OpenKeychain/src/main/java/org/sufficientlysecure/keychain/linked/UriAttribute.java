package org.sufficientlysecure.keychain.linked;

import org.spongycastle.util.Strings;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.WrappedUserAttribute;
import org.sufficientlysecure.keychain.util.Log;

import java.io.IOException;
import java.net.URI;

import android.content.Context;
import android.support.annotation.DrawableRes;

/** The RawLinkedIdentity contains raw parsed data from a Linked Identity subpacket. */
public class UriAttribute {

    public final URI mUri;

    protected UriAttribute(URI uri) {
        mUri = uri;
    }

    public byte[] getEncoded() {
        return Strings.toUTF8ByteArray(mUri.toASCIIString());
    }

    public static UriAttribute fromAttributeData(byte[] data) throws IOException {
        WrappedUserAttribute att = WrappedUserAttribute.fromData(data);

        byte[][] subpackets = att.getSubpackets();
        if (subpackets.length >= 1) {
            return fromSubpacketData(subpackets[0]);
        }

        throw new IOException("no subpacket data");
    }

    static UriAttribute fromSubpacketData(byte[] data) {

        try {
            String uriStr = Strings.fromUTF8ByteArray(data);
            URI uri = URI.create(uriStr);

            LinkedResource res = LinkedTokenResource.fromUri(uri);
            if (res == null) {
                return new UriAttribute(uri);
            }

            return new LinkedAttribute(uri, res);

        } catch (IllegalArgumentException e) {
            Log.e(Constants.TAG, "error parsing uri in (suspected) linked id packet");
            return null;
        }
    }

    public static UriAttribute fromResource (LinkedTokenResource res) {
        return new UriAttribute(res.toUri());
    }


    public WrappedUserAttribute toUserAttribute () {
        return WrappedUserAttribute.fromSubpacket(WrappedUserAttribute.UAT_URI_ATTRIBUTE, getEncoded());
    }

    public @DrawableRes int getDisplayIcon() {
        return R.drawable.ic_warning_grey_24dp;
    }

    public String getDisplayTitle(Context context) {
        return "Unknown Identity";
    }

    public String getDisplayComment(Context context) {
        return null;
    }

}
