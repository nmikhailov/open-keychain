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

package org.sufficientlysecure.keychain.ui.widget;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.KeyRing;
import org.sufficientlysecure.keychain.pgp.PgpKeyHelper;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.util.Log;

public abstract class KeySpinner extends Spinner implements LoaderManager.LoaderCallbacks<Cursor> {
    public interface OnKeyChangedListener {
        public void onKeyChanged(long masterKeyId);
    }

    protected long mSelectedKeyId;
    protected SelectKeyAdapter mAdapter = new SelectKeyAdapter();
    protected OnKeyChangedListener mListener;

    public KeySpinner(Context context) {
        super(context);
        initView();
    }

    public KeySpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public KeySpinner(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView();
    }

    private void initView() {
        setAdapter(mAdapter);
        super.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mListener != null) {
                    mListener.onKeyChanged(id);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mListener != null) {
                    mListener.onKeyChanged(Constants.key.none);
                }
            }
        });
    }

    @Override
    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        throw new UnsupportedOperationException();
    }

    public void setOnKeyChangedListener(OnKeyChangedListener listener) {
        mListener = listener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        reload();
    }

    public void reload() {
        if (getContext() instanceof FragmentActivity) {
            ((FragmentActivity) getContext()).getSupportLoaderManager().restartLoader(0, null, this);
        } else {
            Log.e(Constants.TAG, "KeySpinner must be attached to FragmentActivity, this is " + getContext().getClass());
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    public long getSelectedKeyId() {
        return mSelectedKeyId;
    }

    public void setSelectedKeyId(long selectedKeyId) {
        this.mSelectedKeyId = selectedKeyId;
    }

    protected class SelectKeyAdapter extends BaseAdapter implements SpinnerAdapter {
        private CursorAdapter inner;
        private int mIndexUserId;
        private int mIndexKeyId;
        private int mIndexMasterKeyId;

        public SelectKeyAdapter() {
            inner = new CursorAdapter(null, null, 0) {
                @Override
                public View newView(Context context, Cursor cursor, ViewGroup parent) {
                    return View.inflate(getContext(), R.layout.keyspinner_item, null);
                }

                @Override
                public void bindView(View view, Context context, Cursor cursor) {
                    String[] userId = KeyRing.splitUserId(cursor.getString(mIndexUserId));
                    TextView vKeyName = ((TextView) view.findViewById(R.id.keyspinner_key_name));
                    TextView vKeyStatus = ((TextView) view.findViewById(R.id.keyspinner_key_status));
                    vKeyName.setText(userId[2] == null ? userId[0] : (userId[0] + " (" + userId[2] + ")"));
                    ((TextView) view.findViewById(R.id.keyspinner_key_email)).setText(userId[1]);
                    ((TextView) view.findViewById(R.id.keyspinner_key_id)).setText(PgpKeyHelper.convertKeyIdToHex(cursor.getLong(mIndexKeyId)));
                    String status = getStatus(getContext(), cursor);
                    if (status == null) {
                        vKeyName.setTextColor(Color.BLACK);
                        vKeyStatus.setVisibility(View.GONE);
                        view.setClickable(false);
                    } else {
                        vKeyName.setTextColor(Color.GRAY);
                        vKeyStatus.setVisibility(View.VISIBLE);
                        vKeyStatus.setText(status);
                        // this is a HACK. the trick is, if the element itself is clickable, the
                        // click is not passed on to the view list
                        view.setClickable(true);
                    }
                }

                @Override
                public long getItemId(int position) {
                    try {
                        return ((Cursor) getItem(position)).getLong(mIndexMasterKeyId);
                    } catch (Exception e) {
                        // This can happen on concurrent modification :(
                        return Constants.key.none;
                    }
                }
            };
        }

        public Cursor swapCursor(Cursor newCursor) {
            if (newCursor == null) return inner.swapCursor(null);

            mIndexKeyId = newCursor.getColumnIndex(KeychainContract.KeyRings.KEY_ID);
            mIndexUserId = newCursor.getColumnIndex(KeychainContract.KeyRings.USER_ID);
            mIndexMasterKeyId = newCursor.getColumnIndex(KeychainContract.KeyRings.MASTER_KEY_ID);
            if (newCursor.moveToFirst()) {
                do {
                    if (newCursor.getLong(mIndexMasterKeyId) == mSelectedKeyId) {
                        setSelection(newCursor.getPosition() + 1);
                    }
                } while (newCursor.moveToNext());
            }
            return inner.swapCursor(newCursor);
        }

        @Override
        public int getCount() {
            return inner.getCount() + 1;
        }

        @Override
        public Object getItem(int position) {
            if (position == 0) return null;
            return inner.getItem(position - 1);
        }

        @Override
        public long getItemId(int position) {
            if (position == 0) return Constants.key.none;
            return inner.getItemId(position - 1);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            try {
                View v = getDropDownView(position, convertView, parent);
                v.findViewById(R.id.keyspinner_key_email).setVisibility(View.GONE);
                return v;
            } catch (NullPointerException e) {
                // This is for the preview...
                return View.inflate(getContext(), android.R.layout.simple_list_item_1, null);
            }
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View v;
            if (position == 0) {
                if (convertView == null) {
                    v = inner.newView(null, null, parent);
                } else {
                    v = convertView;
                }
                ((TextView) v.findViewById(R.id.keyspinner_key_name)).setText(R.string.choice_none);
                v.findViewById(R.id.keyspinner_key_email).setVisibility(View.GONE);
                v.findViewById(R.id.keyspinner_key_row).setVisibility(View.GONE);
            } else {
                v = inner.getView(position - 1, convertView, parent);
                v.findViewById(R.id.keyspinner_key_email).setVisibility(View.VISIBLE);
                v.findViewById(R.id.keyspinner_key_row).setVisibility(View.VISIBLE);
            }
            return v;
        }
    }

    /** Return a string which should be the disabled status of the key, or null if the key is enabled. */
    String getStatus(Context context, Cursor cursor) {
        return null;
    }

}
