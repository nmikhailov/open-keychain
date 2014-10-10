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

package org.sufficientlysecure.keychain.operations.results;

import android.app.Activity;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

import com.github.johnpersano.supertoasts.SuperCardToast;
import com.github.johnpersano.supertoasts.SuperToast;
import com.github.johnpersano.supertoasts.SuperToast.Duration;
import com.github.johnpersano.supertoasts.util.OnClickWrapper;
import com.github.johnpersano.supertoasts.util.Style;

import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.ui.LogDisplayActivity;
import org.sufficientlysecure.keychain.ui.LogDisplayFragment;

public class ImportKeyResult extends OperationResult {

    public final int mNewKeys, mUpdatedKeys, mBadKeys, mSecret;
    public final long[] mImportedMasterKeyIds;

    // At least one new key
    public static final int RESULT_OK_NEWKEYS = 8;
    // At least one updated key
    public static final int RESULT_OK_UPDATED = 16;
    // At least one key failed (might still be an overall success)
    public static final int RESULT_WITH_ERRORS = 32;

    // No keys to import...
    public static final int RESULT_FAIL_NOTHING = 64 + 1;

    public boolean isOkBoth() {
        return (mResult & (RESULT_OK_NEWKEYS | RESULT_OK_UPDATED))
                == (RESULT_OK_NEWKEYS | RESULT_OK_UPDATED);
    }

    public boolean isOkNew() {
        return (mResult & RESULT_OK_NEWKEYS) == RESULT_OK_NEWKEYS;
    }

    public boolean isOkUpdated() {
        return (mResult & RESULT_OK_UPDATED) == RESULT_OK_UPDATED;
    }

    public boolean isOkWithErrors() {
        return (mResult & RESULT_WITH_ERRORS) == RESULT_WITH_ERRORS;
    }

    public boolean isFailNothing() {
        return (mResult & RESULT_FAIL_NOTHING) == RESULT_FAIL_NOTHING;
    }

    public long[] getImportedMasterKeyIds() {
        return mImportedMasterKeyIds;
    }

    public ImportKeyResult(Parcel source) {
        super(source);
        mNewKeys = source.readInt();
        mUpdatedKeys = source.readInt();
        mBadKeys = source.readInt();
        mSecret = source.readInt();
        mImportedMasterKeyIds = source.createLongArray();
    }

    public ImportKeyResult(int result, OperationLog log,
                           int newKeys, int updatedKeys, int badKeys, int secret,
                           long[] importedMasterKeyIds) {
        super(result, log);
        mNewKeys = newKeys;
        mUpdatedKeys = updatedKeys;
        mBadKeys = badKeys;
        mSecret = secret;
        mImportedMasterKeyIds = importedMasterKeyIds;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mNewKeys);
        dest.writeInt(mUpdatedKeys);
        dest.writeInt(mBadKeys);
        dest.writeInt(mSecret);
        dest.writeLongArray(mImportedMasterKeyIds);
    }

    public static Creator<ImportKeyResult> CREATOR = new Creator<ImportKeyResult>() {
        public ImportKeyResult createFromParcel(final Parcel source) {
            return new ImportKeyResult(source);
        }

        public ImportKeyResult[] newArray(final int size) {
            return new ImportKeyResult[size];
        }
    };

    public SuperCardToast createNotify(final Activity activity) {

        int resultType = getResult();

        String str;
        int duration, color;

        // Not an overall failure
        if ((resultType & OperationResult.RESULT_ERROR) == 0) {
            String withWarnings;

            duration = Duration.EXTRA_LONG;
            color = Style.GREEN;
            withWarnings = "";

            // Any warnings?
            if ((resultType & ImportKeyResult.RESULT_WARNINGS) > 0) {
                duration = 0;
                color = Style.ORANGE;
                withWarnings += activity.getString(R.string.with_warnings);
            }
            if ((resultType & ImportKeyResult.RESULT_CANCELLED) > 0) {
                duration = 0;
                color = Style.ORANGE;
                withWarnings += activity.getString(R.string.with_cancelled);
            }

            // New and updated keys
            if (isOkBoth()) {
                str = activity.getResources().getQuantityString(
                        R.plurals.import_keys_added_and_updated_1, mNewKeys, mNewKeys);
                str += " " + activity.getResources().getQuantityString(
                        R.plurals.import_keys_added_and_updated_2, mUpdatedKeys, mUpdatedKeys, withWarnings);
            } else if (isOkUpdated()) {
                str = activity.getResources().getQuantityString(
                        R.plurals.import_keys_updated, mUpdatedKeys, mUpdatedKeys, withWarnings);
            } else if (isOkNew()) {
                str = activity.getResources().getQuantityString(
                        R.plurals.import_keys_added, mNewKeys, mNewKeys, withWarnings);
            } else {
                duration = 0;
                color = Style.RED;
                str = "internal error";
            }
            if (isOkWithErrors()) {
                // definitely switch to warning-style message in this case!
                duration = 0;
                color = Style.RED;
                str += " " + activity.getResources().getQuantityString(
                        R.plurals.import_keys_with_errors, mBadKeys, mBadKeys);
            }

        } else {
            duration = 0;
            color = Style.RED;
            if (isFailNothing()) {
                str = activity.getString((resultType & ImportKeyResult.RESULT_CANCELLED) > 0
                        ? R.string.import_error_nothing_cancelled
                        : R.string.import_error_nothing);
            } else {
                str = activity.getResources().getQuantityString(R.plurals.import_error, mBadKeys);
            }
        }

        boolean button = getLog() != null && !getLog().isEmpty();
        SuperCardToast toast = new SuperCardToast(activity,
                button ? SuperToast.Type.BUTTON : SuperToast.Type.STANDARD,
                Style.getStyle(color, SuperToast.Animations.POPUP));
        toast.setText(str);
        toast.setDuration(duration);
        toast.setIndeterminate(duration == 0);
        toast.setSwipeToDismiss(true);
        // If we have a log and it's non-empty, show a View Log button
        if (button) {
            toast.setButtonIcon(R.drawable.ic_action_view_as_list,
                    activity.getResources().getString(R.string.view_log));
            toast.setButtonTextColor(activity.getResources().getColor(R.color.black));
            toast.setTextColor(activity.getResources().getColor(R.color.black));
            toast.setOnClickWrapper(new OnClickWrapper("supercardtoast",
                    new SuperToast.OnClickListener() {
                        @Override
                        public void onClick(View view, Parcelable token) {
                            Intent intent = new Intent(
                                    activity, LogDisplayActivity.class);
                            intent.putExtra(LogDisplayFragment.EXTRA_RESULT, ImportKeyResult.this);
                            activity.startActivity(intent);
                        }
                    }
            ));
        }

        return toast;

    }

}
