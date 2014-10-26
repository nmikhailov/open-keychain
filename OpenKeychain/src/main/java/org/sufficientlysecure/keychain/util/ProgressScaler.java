/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.sufficientlysecure.keychain.util;

import org.sufficientlysecure.keychain.pgp.Progressable;

/**
 * This is a simple class that wraps a Progressable, scaling the progress
 * values into a specified range.
 */
public class ProgressScaler implements Progressable {

    final Progressable mWrapped;
    final int mFrom, mTo, mMax;

    public ProgressScaler() {
        mWrapped = null;
        mFrom = mTo = mMax = 0;
    }
    public ProgressScaler(Progressable wrapped, int from, int to, int max) {
        this.mWrapped = wrapped;
        this.mFrom = from;
        this.mTo = to;
        this.mMax = max;
    }

    /**
     * Set progress of ProgressDialog by sending message to handler on UI thread
     */
    public void setProgress(String message, int progress, int max) {
        if (mWrapped != null) {
            mWrapped.setProgress(message, mFrom + progress * (mTo - mFrom) / max, mMax);
        }
    }

    public void setProgress(int resourceId, int progress, int max) {
        if (mWrapped != null) {
            mWrapped.setProgress(resourceId, mFrom + progress * (mTo - mFrom) / max, mMax);
        }
    }

    public void setProgress(int progress, int max) {
        if (mWrapped != null) {
            mWrapped.setProgress(mFrom + progress * (mTo - mFrom) / max, mMax);
        }
    }

    @Override
    public void setPreventCancel() {
        if (mWrapped != null) {
            mWrapped.setPreventCancel();
        }
    }
}
