/*
 * Copyright (C) 2013-2014 Dominik Schürmann <dominik@dominikschuermann.de>
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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.KeyRing;

import java.util.List;

public class UserIdsArrayAdapter extends ArrayAdapter<String> {
    protected LayoutInflater mInflater;
    protected Activity mActivity;

    protected List<String> mData;

    static class ViewHolder {
        public TextView vName;
        public TextView vAddress;
        public TextView vComment;
        public ImageView vVerified;
        public ImageView vHasChanges;
        public CheckBox vCheckBox;
    }

    public UserIdsArrayAdapter(Activity activity) {
        super(activity, -1);
        mActivity = activity;
        mInflater = (LayoutInflater) activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void setData(List<String> data) {
        clear();
        if (data != null) {
            this.mData = data;

            // add data to extended ArrayAdapter
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                addAll(data);
            } else {
                for (String entry : data) {
                    add(entry);
                }
            }
        }
    }

    public List<String> getData() {
        return mData;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        String entry = mData.get(position);
        ViewHolder holder;
        if (convertView == null) {
            holder = new ViewHolder();
            convertView = mInflater.inflate(R.layout.view_key_userids_item, null);
            holder.vName = (TextView) convertView.findViewById(R.id.userId);
            holder.vAddress = (TextView) convertView.findViewById(R.id.address);
            holder.vComment = (TextView) convertView.findViewById(R.id.comment);
            holder.vVerified = (ImageView) convertView.findViewById(R.id.certified);
            holder.vHasChanges = (ImageView) convertView.findViewById(R.id.has_changes);
            holder.vCheckBox = (CheckBox) convertView.findViewById(R.id.checkBox);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        // user id
        String[] splitUserId = KeyRing.splitUserId(entry);
        if (splitUserId[0] != null) {
            holder.vName.setText(splitUserId[0]);
        } else {
            holder.vName.setText(R.string.user_id_no_name);
        }
        if (splitUserId[1] != null) {
            holder.vAddress.setText(splitUserId[1]);
            holder.vAddress.setVisibility(View.VISIBLE);
        } else {
            holder.vAddress.setVisibility(View.GONE);
        }
        if (splitUserId[2] != null) {
            holder.vComment.setText(splitUserId[2]);
            holder.vComment.setVisibility(View.VISIBLE);
        } else {
            holder.vComment.setVisibility(View.GONE);
        }

        holder.vCheckBox.setVisibility(View.GONE);

        holder.vVerified.setImageResource(R.drawable.key_certify_ok_depth0);

        // all items are "new"
        holder.vHasChanges.setVisibility(View.VISIBLE);

        return convertView;
    }

}
