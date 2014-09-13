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

package org.sufficientlysecure.keychain.ui.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.support.v4.widget.CursorAdapter;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.helper.OtherHelper;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey.SecretKeyType;
import org.sufficientlysecure.keychain.pgp.PgpKeyHelper;
import org.sufficientlysecure.keychain.provider.KeychainContract.Keys;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class SubkeysAdapter extends CursorAdapter {
    private LayoutInflater mInflater;
    private SaveKeyringParcel mSaveKeyringParcel;

    private boolean hasAnySecret;
    private ColorStateList mDefaultTextColor;

    public static final String[] SUBKEYS_PROJECTION = new String[]{
            Keys._ID,
            Keys.KEY_ID,
            Keys.RANK,
            Keys.ALGORITHM,
            Keys.KEY_SIZE,
            Keys.KEY_CURVE_OID,
            Keys.HAS_SECRET,
            Keys.CAN_CERTIFY,
            Keys.CAN_ENCRYPT,
            Keys.CAN_SIGN,
            Keys.IS_REVOKED,
            Keys.CREATION,
            Keys.EXPIRY,
            Keys.FINGERPRINT
    };
    private static final int INDEX_ID = 0;
    private static final int INDEX_KEY_ID = 1;
    private static final int INDEX_RANK = 2;
    private static final int INDEX_ALGORITHM = 3;
    private static final int INDEX_KEY_SIZE = 4;
    private static final int INDEX_KEY_CURVE_OID = 5;
    private static final int INDEX_HAS_SECRET = 6;
    private static final int INDEX_CAN_CERTIFY = 7;
    private static final int INDEX_CAN_ENCRYPT = 8;
    private static final int INDEX_CAN_SIGN = 9;
    private static final int INDEX_IS_REVOKED = 10;
    private static final int INDEX_CREATION = 11;
    private static final int INDEX_EXPIRY = 12;
    private static final int INDEX_FINGERPRINT = 13;

    public SubkeysAdapter(Context context, Cursor c, int flags,
                          SaveKeyringParcel saveKeyringParcel) {
        super(context, c, flags);

        mInflater = LayoutInflater.from(context);
        mSaveKeyringParcel = saveKeyringParcel;
    }

    public SubkeysAdapter(Context context, Cursor c, int flags) {
        this(context, c, flags, null);
    }

    public long getKeyId(int position) {
        mCursor.moveToPosition(position);
        return mCursor.getLong(INDEX_KEY_ID);
    }

    public long getCreationDate(int position) {
        mCursor.moveToPosition(position);
        return mCursor.getLong(INDEX_CREATION);
    }

    public Long getExpiryDate(int position) {
        mCursor.moveToPosition(position);
        if (mCursor.isNull(INDEX_EXPIRY)) {
            return null;
        } else {
            return mCursor.getLong(INDEX_EXPIRY);
        }
    }

    @Override
    public Cursor swapCursor(Cursor newCursor) {
        hasAnySecret = false;
        if (newCursor != null && newCursor.moveToFirst()) {
            do {
                SecretKeyType hasSecret = SecretKeyType.fromNum(newCursor.getInt(INDEX_HAS_SECRET));
                if (hasSecret.isUsable()) {
                    hasAnySecret = true;
                    break;
                }
            } while (newCursor.moveToNext());
        }

        return super.swapCursor(newCursor);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        TextView vKeyId = (TextView) view.findViewById(R.id.subkey_item_key_id);
        TextView vKeyDetails = (TextView) view.findViewById(R.id.subkey_item_details);
        TextView vKeyExpiry = (TextView) view.findViewById(R.id.subkey_item_expiry);
        ImageView vCertifyIcon = (ImageView) view.findViewById(R.id.subkey_item_ic_certify);
        ImageView vEncryptIcon = (ImageView) view.findViewById(R.id.subkey_item_ic_encrypt);
        ImageView vSignIcon = (ImageView) view.findViewById(R.id.subkey_item_ic_sign);
        ImageView vRevokedIcon = (ImageView) view.findViewById(R.id.subkey_item_ic_revoked);
        ImageView vEditImage = (ImageView) view.findViewById(R.id.subkey_item_edit_image);

        // not used:
        ImageView deleteImage = (ImageView) view.findViewById(R.id.subkey_item_delete_button);
        deleteImage.setVisibility(View.GONE);

        long keyId = cursor.getLong(INDEX_KEY_ID);
        String keyIdStr = PgpKeyHelper.convertKeyIdToHex(keyId);
        vKeyId.setText(keyIdStr);

        // may be set with additional "stripped" later on
        SpannableStringBuilder algorithmStr = new SpannableStringBuilder();
        algorithmStr.append(PgpKeyHelper.getAlgorithmInfo(
                context,
                cursor.getInt(INDEX_ALGORITHM),
                cursor.getInt(INDEX_KEY_SIZE),
                cursor.getString(INDEX_KEY_CURVE_OID)
        ));

        if (mSaveKeyringParcel != null && mSaveKeyringParcel.mStripSubKeys.contains(keyId)) {
            algorithmStr.append(", ");
            final SpannableString boldStripped = new SpannableString(
                    context.getString(R.string.key_stripped)
            );
            boldStripped.setSpan(new StyleSpan(Typeface.BOLD), 0, boldStripped.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            algorithmStr.append(boldStripped);
        } else {
            switch (SecretKeyType.fromNum(cursor.getInt(INDEX_HAS_SECRET))) {
                case GNU_DUMMY:
                    algorithmStr.append(", ");
                    algorithmStr.append(context.getString(R.string.key_stripped));
                    break;
                case DIVERT_TO_CARD:
                    algorithmStr.append(", ");
                    algorithmStr.append(context.getString(R.string.key_divert));
                    break;
                case PASSPHRASE_EMPTY:
                    algorithmStr.append(", ");
                    algorithmStr.append(context.getString(R.string.key_no_passphrase));
                    break;
                case UNAVAILABLE:
                    // don't show this on pub keys
                    //algorithmStr += ", " + context.getString(R.string.key_unavailable);
                    break;
            }
        }
        vKeyDetails.setText(algorithmStr, TextView.BufferType.SPANNABLE);

        boolean isMasterKey = cursor.getInt(INDEX_RANK) == 0;
        if (isMasterKey) {
            vKeyId.setTypeface(null, Typeface.BOLD);
        } else {
            vKeyId.setTypeface(null, Typeface.NORMAL);
        }

        // Set icons according to properties
        vCertifyIcon.setVisibility(cursor.getInt(INDEX_CAN_CERTIFY) != 0 ? View.VISIBLE : View.GONE);
        vEncryptIcon.setVisibility(cursor.getInt(INDEX_CAN_ENCRYPT) != 0 ? View.VISIBLE : View.GONE);
        vSignIcon.setVisibility(cursor.getInt(INDEX_CAN_SIGN) != 0 ? View.VISIBLE : View.GONE);
        // TODO: missing icon for authenticate

        boolean isRevoked = cursor.getInt(INDEX_IS_REVOKED) > 0;

        Date expiryDate = null;
        if (!cursor.isNull(INDEX_EXPIRY)) {
            expiryDate = new Date(cursor.getLong(INDEX_EXPIRY) * 1000);
        }

        // for edit key
        if (mSaveKeyringParcel != null) {
            boolean revokeThisSubkey = (mSaveKeyringParcel.mRevokeSubKeys.contains(keyId));

            if (revokeThisSubkey) {
                if (!isRevoked) {
                    isRevoked = true;
                }
            }

            SaveKeyringParcel.SubkeyChange subkeyChange = mSaveKeyringParcel.getSubkeyChange(keyId);
            if (subkeyChange != null) {
                if (subkeyChange.mExpiry == null || subkeyChange.mExpiry == 0L) {
                    expiryDate = null;
                } else {
                    expiryDate = new Date(subkeyChange.mExpiry * 1000);
                }
            }

            vEditImage.setVisibility(View.VISIBLE);
        } else {
            vEditImage.setVisibility(View.GONE);
        }

        boolean isExpired;
        if (expiryDate != null) {
            isExpired = expiryDate.before(new Date());
            Calendar expiryCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            expiryCal.setTime(expiryDate);
            // convert from UTC to time zone of device
            expiryCal.setTimeZone(TimeZone.getDefault());

            vKeyExpiry.setText(context.getString(R.string.label_expiry) + ": "
                    + DateFormat.getDateFormat(context).format(expiryCal.getTime()));
        } else {
            isExpired = false;

            vKeyExpiry.setText(context.getString(R.string.label_expiry) + ": " + context.getString(R.string.none));
        }

        if (isRevoked) {
            vRevokedIcon.setVisibility(View.VISIBLE);
        } else {
            vKeyId.setTextColor(mDefaultTextColor);
            vKeyDetails.setTextColor(mDefaultTextColor);
            vKeyExpiry.setTextColor(mDefaultTextColor);

            vRevokedIcon.setVisibility(View.GONE);
        }

        // if key is expired or revoked, strike through text
        boolean isInvalid = isRevoked || isExpired;
        if (isInvalid) {
            vKeyId.setText(OtherHelper.strikeOutText(vKeyId.getText()));
            vKeyDetails.setText(OtherHelper.strikeOutText(vKeyDetails.getText()));
            vKeyExpiry.setText(OtherHelper.strikeOutText(vKeyExpiry.getText()));
        }
        vKeyId.setEnabled(!isInvalid);
        vKeyDetails.setEnabled(!isInvalid);
        vKeyExpiry.setEnabled(!isInvalid);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View view = mInflater.inflate(R.layout.view_key_subkey_item, null);
        if (mDefaultTextColor == null) {
            TextView keyId = (TextView) view.findViewById(R.id.subkey_item_key_id);
            mDefaultTextColor = keyId.getTextColors();
        }
        return view;
    }

    // Disable selection of items, http://stackoverflow.com/a/4075045
    @Override
    public boolean areAllItemsEnabled() {
        if (mSaveKeyringParcel == null) {
            return false;
        } else {
            return super.areAllItemsEnabled();
        }
    }

    // Disable selection of items, http://stackoverflow.com/a/4075045
    @Override
    public boolean isEnabled(int position) {
        if (mSaveKeyringParcel == null) {
            return false;
        } else {
            return super.isEnabled(position);
        }
    }

}
