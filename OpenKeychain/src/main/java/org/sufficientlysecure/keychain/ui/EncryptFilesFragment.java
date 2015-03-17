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

package org.sufficientlysecure.keychain.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.provider.TemporaryStorageProvider;
import org.sufficientlysecure.keychain.ui.util.FormattingUtils;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.util.FileHelper;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class EncryptFilesFragment extends Fragment implements EncryptActivityInterface.UpdateListener {
    public static final String ARG_URIS = "uris";

    private static final int REQUEST_CODE_INPUT = 0x00007003;
    private static final int REQUEST_CODE_OUTPUT = 0x00007007;

    private EncryptActivityInterface mEncryptInterface;

    // view
    private View mAddView;
    private ListView mSelectedFiles;
    private SelectedFilesAdapter mAdapter = new SelectedFilesAdapter();
    private final Map<Uri, Bitmap> thumbnailCache = new HashMap<>();

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mEncryptInterface = (EncryptActivityInterface) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement EncryptActivityInterface");
        }
    }

    /**
     * Inflate the layout for this fragment
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.encrypt_files_fragment, container, false);

        mAddView = inflater.inflate(R.layout.file_list_entry_add, null);
        mAddView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addInputUri();
            }
        });
        mSelectedFiles = (ListView) view.findViewById(R.id.selected_files_list);
        mSelectedFiles.addFooterView(mAddView);
        mSelectedFiles.setAdapter(mAdapter);

        return view;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);
    }

    private void addInputUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            FileHelper.openDocument(EncryptFilesFragment.this, "*/*", true, REQUEST_CODE_INPUT);
        } else {
            FileHelper.openFile(EncryptFilesFragment.this, mEncryptInterface.getInputUris().isEmpty() ?
                            null : mEncryptInterface.getInputUris().get(mEncryptInterface.getInputUris().size() - 1),
                    "*/*", REQUEST_CODE_INPUT);
        }
    }

    private void addInputUri(Uri inputUri) {
        if (inputUri == null) {
            return;
        }

        if (mEncryptInterface.getInputUris().contains(inputUri)) {
            Notify.showNotify(getActivity(),
                    getActivity().getString(R.string.error_file_added_already, FileHelper.getFilename(getActivity(), inputUri)),
                    Notify.Style.ERROR);
            return;
        }

        mEncryptInterface.getInputUris().add(inputUri);
        mEncryptInterface.notifyUpdate();
        mSelectedFiles.requestFocus();
    }

    private void delInputUri(int position) {
        mEncryptInterface.getInputUris().remove(position);
        mEncryptInterface.notifyUpdate();
        mSelectedFiles.requestFocus();
    }

    private void showOutputFileDialog() {
        if (mEncryptInterface.getInputUris().size() > 1 || mEncryptInterface.getInputUris().isEmpty()) {
            throw new IllegalStateException();
        }
        Uri inputUri = mEncryptInterface.getInputUris().get(0);
        String targetName =
                (mEncryptInterface.isEncryptFilenames() ? "1" : FileHelper.getFilename(getActivity(), inputUri))
                        + (mEncryptInterface.isUseArmor() ? Constants.FILE_EXTENSION_ASC : Constants.FILE_EXTENSION_PGP_MAIN);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            File file = new File(inputUri.getPath());
            File parentDir = file.exists() ? file.getParentFile() : Constants.Path.APP_DIR;
            File targetFile = new File(parentDir, targetName);
            FileHelper.saveFile(this, getString(R.string.title_encrypt_to_file),
                    getString(R.string.specify_file_to_encrypt_to), targetFile, REQUEST_CODE_OUTPUT);
        } else {
            FileHelper.saveDocument(this, "*/*", targetName, REQUEST_CODE_OUTPUT);
        }
    }

    private void encryptClicked(boolean share) {
        if (mEncryptInterface.getInputUris().isEmpty()) {
            Notify.showNotify(getActivity(), R.string.error_no_file_selected, Notify.Style.ERROR);
            return;
        }
        if (share) {
            mEncryptInterface.getOutputUris().clear();
            int filenameCounter = 1;
            for (Uri uri : mEncryptInterface.getInputUris()) {
                String targetName =
                        (mEncryptInterface.isEncryptFilenames() ? String.valueOf(filenameCounter) : FileHelper.getFilename(getActivity(), uri))
                                + (mEncryptInterface.isUseArmor() ? Constants.FILE_EXTENSION_ASC : Constants.FILE_EXTENSION_PGP_MAIN);
                mEncryptInterface.getOutputUris().add(TemporaryStorageProvider.createFile(getActivity(), targetName));
                filenameCounter++;
            }
            mEncryptInterface.startEncrypt(true);
        } else {
            if (mEncryptInterface.getInputUris().size() > 1) {
                Notify.showNotify(getActivity(), R.string.error_multi_not_supported, Notify.Style.ERROR);
                return;
            }
            showOutputFileDialog();
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public boolean handleClipData(Intent data) {
        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) addInputUri(uri);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.encrypt_save: {
                encryptClicked(false);
                break;
            }
            case R.id.encrypt_share: {
                encryptClicked(true);
                break;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_INPUT: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT || !handleClipData(data)) {
                        addInputUri(data.getData());
                    }
                }
                return;
            }
            case REQUEST_CODE_OUTPUT: {
                // This happens after output file was selected, so start our operation
                if (resultCode == Activity.RESULT_OK && data != null) {
                    mEncryptInterface.getOutputUris().clear();
                    mEncryptInterface.getOutputUris().add(data.getData());
                    mEncryptInterface.notifyUpdate();
                    mEncryptInterface.startEncrypt(false);
                }
                return;
            }

            default: {
                super.onActivityResult(requestCode, resultCode, data);

                break;
            }
        }
    }

    @Override
    public void onNotifyUpdate() {
        // Clear cache if needed
        for (Uri uri : new HashSet<>(thumbnailCache.keySet())) {
            if (!mEncryptInterface.getInputUris().contains(uri)) {
                thumbnailCache.remove(uri);
            }
        }

        mAdapter.notifyDataSetChanged();
    }

    private class SelectedFilesAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mEncryptInterface.getInputUris().size();
        }

        @Override
        public Object getItem(int position) {
            return mEncryptInterface.getInputUris().get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).hashCode();
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            Uri inputUri = mEncryptInterface.getInputUris().get(position);
            View view;
            if (convertView == null) {
                view = getActivity().getLayoutInflater().inflate(R.layout.file_list_entry, null);
            } else {
                view = convertView;
            }
            ((TextView) view.findViewById(R.id.filename)).setText(FileHelper.getFilename(getActivity(), inputUri));
            long size = FileHelper.getFileSize(getActivity(), inputUri);
            if (size == -1) {
                ((TextView) view.findViewById(R.id.filesize)).setText("");
            } else {
                ((TextView) view.findViewById(R.id.filesize)).setText(FileHelper.readableFileSize(size));
            }
            view.findViewById(R.id.action_remove_file_from_list).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    delInputUri(position);
                }
            });
            int px = FormattingUtils.dpToPx(getActivity(), 48);
            if (!thumbnailCache.containsKey(inputUri)) {
                thumbnailCache.put(inputUri, FileHelper.getThumbnail(getActivity(), inputUri, new Point(px, px)));
            }
            Bitmap bitmap = thumbnailCache.get(inputUri);
            if (bitmap != null) {
                ((ImageView) view.findViewById(R.id.thumbnail)).setImageBitmap(bitmap);
            } else {
                ((ImageView) view.findViewById(R.id.thumbnail)).setImageResource(R.drawable.ic_doc_generic_am);
            }
            return view;
        }
    }
}
