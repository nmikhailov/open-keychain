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


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.app.Activity;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnDismissListener;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ViewAnimator;

import com.cocosw.bottomsheet.BottomSheet;
import org.openintents.openpgp.OpenPgpMetadata;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.InputDataResult;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerifyInputParcel;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.service.InputDataParcel;
import org.sufficientlysecure.keychain.ui.base.QueueingCryptoOperationFragment;
// this import NEEDS to be above the ViewModel AND SubViewHolder one, or it won't compile! (as of 16.09.15)
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils.StatusHolder;
import org.sufficientlysecure.keychain.ui.DecryptListFragment.ViewHolder.SubViewHolder;
import org.sufficientlysecure.keychain.ui.DecryptListFragment.DecryptFilesAdapter.ViewModel;
import org.sufficientlysecure.keychain.ui.adapter.SpacesItemDecoration;
import org.sufficientlysecure.keychain.ui.util.FormattingUtils;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.util.Notify.Style;
import org.sufficientlysecure.keychain.util.FileHelper;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.ParcelableHashMap;


public class DecryptListFragment
        extends QueueingCryptoOperationFragment<InputDataParcel,InputDataResult>
        implements OnMenuItemClickListener {

    public static final String ARG_INPUT_URIS = "input_uris";
    public static final String ARG_OUTPUT_URIS = "output_uris";
    public static final String ARG_CANCELLED_URIS = "cancelled_uris";
    public static final String ARG_RESULTS = "results";

    private static final int REQUEST_CODE_OUTPUT = 0x00007007;
    public static final String ARG_CURRENT_URI = "current_uri";

    private ArrayList<Uri> mInputUris;
    private HashMap<Uri, InputDataResult> mInputDataResults;
    private ArrayList<Uri> mPendingInputUris;
    private ArrayList<Uri> mCancelledInputUris;

    private Uri mCurrentInputUri;

    private DecryptFilesAdapter mAdapter;
    private Uri mCurrentSaveFileUri;

    /**
     * Creates new instance of this fragment
     */
    public static DecryptListFragment newInstance(ArrayList<Uri> uris) {
        DecryptListFragment frag = new DecryptListFragment();

        Bundle args = new Bundle();
        args.putParcelableArrayList(ARG_INPUT_URIS, uris);
        frag.setArguments(args);

        return frag;
    }

    public DecryptListFragment() {
        super(null);
    }

    /**
     * Inflate the layout for this fragment
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.decrypt_files_list_fragment, container, false);

        RecyclerView vFilesList = (RecyclerView) view.findViewById(R.id.decrypted_files_list);

        vFilesList.addItemDecoration(new SpacesItemDecoration(
                FormattingUtils.dpToPx(getActivity(), 4)));
        vFilesList.setHasFixedSize(true);
        // TODO make this a grid, for tablets!
        vFilesList.setLayoutManager(new LinearLayoutManager(getActivity()));
        vFilesList.setItemAnimator(new DefaultItemAnimator());

        mAdapter = new DecryptFilesAdapter(this);
        vFilesList.setAdapter(mAdapter);

        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelableArrayList(ARG_INPUT_URIS, mInputUris);

        HashMap<Uri,InputDataResult> results = new HashMap<>(mInputUris.size());
        for (Uri uri : mInputUris) {
            if (mPendingInputUris.contains(uri)) {
                continue;
            }
            InputDataResult result = mAdapter.getItemResult(uri);
            if (result != null) {
                results.put(uri, result);
            }
        }

        outState.putParcelable(ARG_RESULTS, new ParcelableHashMap<>(results));
        outState.putParcelable(ARG_OUTPUT_URIS, new ParcelableHashMap<>(mInputDataResults));
        outState.putParcelableArrayList(ARG_CANCELLED_URIS, mCancelledInputUris);
        outState.putParcelable(ARG_CURRENT_URI, mCurrentInputUri);

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        Bundle args = savedInstanceState != null ? savedInstanceState : getArguments();

        ArrayList<Uri> inputUris = getArguments().getParcelableArrayList(ARG_INPUT_URIS);
        ArrayList<Uri> cancelledUris = args.getParcelableArrayList(ARG_CANCELLED_URIS);
        ParcelableHashMap<Uri,InputDataResult> results = args.getParcelable(ARG_RESULTS);
        Uri currentInputUri = args.getParcelable(ARG_CURRENT_URI);

        displayInputUris(inputUris, currentInputUri, cancelledUris,
                results != null ? results.getMap() : null
        );
    }

    private void displayInputUris(ArrayList<Uri> inputUris, Uri currentInputUri,
            ArrayList<Uri> cancelledUris, HashMap<Uri,InputDataResult> results) {

        mInputUris = inputUris;
        mCurrentInputUri = currentInputUri;
        mInputDataResults = results != null ? results : new HashMap<Uri,InputDataResult>(inputUris.size());
        mCancelledInputUris = cancelledUris != null ? cancelledUris : new ArrayList<Uri>();

        mPendingInputUris = new ArrayList<>();

        for (final Uri uri : inputUris) {
            mAdapter.add(uri);

            if (uri.equals(mCurrentInputUri)) {
                continue;
            }

            if (mCancelledInputUris.contains(uri)) {
                mAdapter.setCancelled(uri, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        retryUri(uri);
                    }
                });
                continue;
            }

            if (results != null && results.containsKey(uri)) {
                processResult(uri);
            } else {
                mPendingInputUris.add(uri);
            }
        }

        if (mCurrentInputUri == null) {
            cryptoOperation();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_OUTPUT: {
                // This happens after output file was selected, so start our operation
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri saveUri = data.getData();
                    saveFile(saveUri);
                    mCurrentInputUri = null;
                }
                return;
            }

            default: {
                super.onActivityResult(requestCode, resultCode, data);
            }
        }
    }

    private void saveFileDialog(InputDataResult result, int index) {

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        OpenPgpMetadata metadata = result.mMetadata.get(index);
        Uri saveUri = Uri.fromFile(activity.getExternalFilesDir(metadata.getMimeType()));
        mCurrentSaveFileUri = result.getOutputUris().get(index);

        String filename = metadata.getFilename();
        if (filename == null) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(metadata.getMimeType());
            filename = "decrypted" + (ext != null ? "."+ext : "");
        }

        FileHelper.saveDocument(this, filename, saveUri, metadata.getMimeType(),
                R.string.title_decrypt_to_file, R.string.specify_file_to_decrypt_to, REQUEST_CODE_OUTPUT);
    }

    private void saveFile(Uri saveUri) {
        if (mCurrentSaveFileUri == null) {
            return;
        }

        Uri decryptedFileUri = mCurrentSaveFileUri;
        mCurrentInputUri = null;

        hideKeyboard();

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        try {
            FileHelper.copyUriData(activity, decryptedFileUri, saveUri);
            Notify.create(activity, R.string.file_saved, Style.OK).show();
        } catch (IOException e) {
            Log.e(Constants.TAG, "error saving file", e);
            Notify.create(activity, R.string.error_saving_file, Style.ERROR).show();
        }
    }

    @Override
    public boolean onCryptoSetProgress(String msg, int progress, int max) {
        mAdapter.setProgress(mCurrentInputUri, progress, max, msg);
        return true;
    }

    @Override
    public void onQueuedOperationError(InputDataResult result) {
        final Uri uri = mCurrentInputUri;
        mCurrentInputUri = null;

        mAdapter.addResult(uri, result);

        cryptoOperation();
    }

    @Override
    public void onQueuedOperationSuccess(InputDataResult result) {
        Uri uri = mCurrentInputUri;
        mCurrentInputUri = null;

        mInputDataResults.put(uri, result);
        processResult(uri);

        cryptoOperation();
    }

    @Override
    public void onCryptoOperationCancelled() {
        super.onCryptoOperationCancelled();

        final Uri uri = mCurrentInputUri;
        mCurrentInputUri = null;

        mCancelledInputUris.add(uri);
        mAdapter.setCancelled(uri, new OnClickListener() {
            @Override
            public void onClick(View v) {
                retryUri(uri);
            }
        });

        cryptoOperation();

    }

    HashMap<Uri,Drawable> mIconCache = new HashMap<>();

    private void processResult(final Uri uri) {

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {

                InputDataResult result = mInputDataResults.get(uri);

                Context context = getActivity();
                if (context == null) {
                    return null;
                }

                for (int i = 0; i < result.getOutputUris().size(); i++) {

                    Uri outputUri = result.getOutputUris().get(i);
                    if (mIconCache.containsKey(outputUri)) {
                        continue;
                    }

                    OpenPgpMetadata metadata = result.mMetadata.get(i);
                    String type = metadata.getMimeType();

                    Drawable icon = null;

                    if (ClipDescription.compareMimeTypes(type, "text/plain")) {
                        // noinspection deprecation, this should be called from Context, but not available in minSdk
                        icon = getResources().getDrawable(R.drawable.ic_chat_black_24dp);
                    } else if (ClipDescription.compareMimeTypes(type, "image/*")) {
                        int px = FormattingUtils.dpToPx(context, 48);
                        Bitmap bitmap = FileHelper.getThumbnail(context, outputUri, new Point(px, px));
                        icon = new BitmapDrawable(context.getResources(), bitmap);
                    } else {
                        final Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(outputUri, type);

                        final List<ResolveInfo> matches =
                                context.getPackageManager().queryIntentActivities(intent, 0);
                        // noinspection LoopStatementThatDoesntLoop
                        for (ResolveInfo match : matches) {
                            icon = match.loadIcon(getActivity().getPackageManager());
                            break;
                        }
                    }

                    if (icon != null) {
                        mIconCache.put(outputUri, icon);
                    }

                }

                return null;

            }

            @Override
            protected void onPostExecute(Void v) {
                InputDataResult result = mInputDataResults.get(uri);
                mAdapter.addResult(uri, result);
            }
        }.execute();

    }

    public void retryUri(Uri uri) {

        // never interrupt running operations!
        if (mCurrentInputUri != null) {
            return;
        }

        // un-cancel this one
        mCancelledInputUris.remove(uri);
        mPendingInputUris.add(uri);
        mAdapter.setCancelled(uri, null);

        cryptoOperation();

    }

    public void displayBottomSheet(final InputDataResult result, final int index) {

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        new BottomSheet.Builder(activity).sheet(R.menu.decrypt_bottom_sheet).listener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.decrypt_open:
                        displayWithViewIntent(result, index, false);
                        break;
                    case R.id.decrypt_share:
                        displayWithViewIntent(result, index, true);
                        break;
                    case R.id.decrypt_save:
                        saveFileDialog(result, index);
                        break;
                }
                return false;
            }
        }).grid().show();

    }

    public void displayWithViewIntent(InputDataResult result, int index, boolean share) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        Uri outputUri = result.getOutputUris().get(index);
        OpenPgpMetadata metadata = result.mMetadata.get(index);

        // text/plain is a special case where we extract the uri content into
        // the EXTRA_TEXT extra ourselves, and display a chooser which includes
        // OpenKeychain's internal viewer
        if ("text/plain".equals(metadata.getMimeType())) {

            if (share) {
                try {
                    String plaintext = FileHelper.readTextFromUri(activity, outputUri, null);

                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, plaintext);
                    startActivity(intent);

                } catch (IOException e) {
                    Notify.create(activity, R.string.error_preparing_data, Style.ERROR).show();
                }

                return;
            }

            Intent intent = new Intent(activity, DisplayTextActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(outputUri, "text/plain");
            intent.putExtra(DisplayTextActivity.EXTRA_METADATA, result.mDecryptVerifyResult);
            activity.startActivity(intent);

        } else {

            Intent intent;
            if (share) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.setType(metadata.getMimeType());
                intent.putExtra(Intent.EXTRA_STREAM, outputUri);
            } else {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(outputUri, metadata.getMimeType());
            }

            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooserIntent = Intent.createChooser(intent, getString(R.string.intent_show));
            chooserIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(chooserIntent);
        }

    }

    @Override
    public InputDataParcel createOperationInput() {

        if (mCurrentInputUri == null) {
            if (mPendingInputUris.isEmpty()) {
                // nothing left to do
                return null;
            }

            mCurrentInputUri = mPendingInputUris.remove(0);
        }

        Log.d(Constants.TAG, "mInputUri=" + mCurrentInputUri);

        PgpDecryptVerifyInputParcel decryptInput = new PgpDecryptVerifyInputParcel()
                .setAllowSymmetricDecryption(true);
        return new InputDataParcel(mCurrentInputUri, decryptInput);

    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        if (mAdapter.mMenuClickedModel == null || !mAdapter.mMenuClickedModel.hasResult()) {
            return false;
        }

        // don't process menu items until all items are done!
        if (!mPendingInputUris.isEmpty()) {
            return true;
        }

        Activity activity = getActivity();
        if (activity == null) {
            return false;
        }

        ViewModel model = mAdapter.mMenuClickedModel;
        switch (menuItem.getItemId()) {
            case R.id.view_log:
                Intent intent = new Intent(activity, LogDisplayActivity.class);
                intent.putExtra(LogDisplayFragment.EXTRA_RESULT, model.mResult);
                activity.startActivity(intent);
                return true;
            case R.id.decrypt_delete:
                deleteFile(activity, model.mInputUri);
                return true;
            /*
            case R.id.decrypt_share:
                displayWithViewIntent(model.mResult, 0, true);
                return true;
            case R.id.decrypt_save:
                OpenPgpMetadata metadata = model.mResult.mDecryptVerifyResult.getDecryptionMetadata();
                if (metadata == null) {
                    return true;
                }
                mCurrentInputUri = model.mInputUri;
                FileHelper.saveDocument(this, metadata.getFilename(), model.mInputUri, metadata.getMimeType(),
                        R.string.title_decrypt_to_file, R.string.specify_file_to_decrypt_to, REQUEST_CODE_OUTPUT);
                return true;
            */
        }
        return false;
    }

    private void deleteFile(Activity activity, Uri uri) {

        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            if (file.delete()) {
                Notify.create(activity, R.string.file_delete_ok, Style.OK).show();
            } else {
                Notify.create(activity, R.string.file_delete_none, Style.WARN).show();
            }
            return;
        }

        if ("content".equals(uri.getScheme())) {
            try {
                int deleted = activity.getContentResolver().delete(uri, null, null);
                if (deleted > 0) {
                    Notify.create(activity, R.string.file_delete_ok, Style.OK).show();
                } else {
                    Notify.create(activity, R.string.file_delete_none, Style.WARN).show();
                }
            } catch (Exception e) {
                Log.e(Constants.TAG, "exception deleting file", e);
                Notify.create(activity, R.string.file_delete_exception, Style.ERROR).show();
            }
            return;
        }

        Notify.create(activity, R.string.file_delete_exception, Style.ERROR).show();

    }

    public class DecryptFilesAdapter extends RecyclerView.Adapter<ViewHolder> {
        private ArrayList<ViewModel> mDataset;
        private OnMenuItemClickListener mMenuItemClickListener;
        private ViewModel mMenuClickedModel;

        public class ViewModel {
            Uri mInputUri;
            InputDataResult mResult;

            int mProgress, mMax;
            String mProgressMsg;
            OnClickListener mCancelled;

            ViewModel(Uri uri) {
                mInputUri = uri;
                mProgress = 0;
                mMax = 100;
                mCancelled = null;
            }

            void addResult(InputDataResult result) {
                mResult = result;
            }

            boolean hasResult() {
                return mResult != null;
            }

            void setCancelled(OnClickListener retryListener) {
                mCancelled = retryListener;
            }

            void setProgress(int progress, int max, String msg) {
                if (msg != null) {
                    mProgressMsg = msg;
                }
                mProgress = progress;
                mMax = max;
            }

            // Depends on inputUri only
            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                ViewModel viewModel = (ViewModel) o;
                return !(mInputUri != null ? !mInputUri.equals(viewModel.mInputUri)
                        : viewModel.mInputUri != null);
            }

            // Depends on inputUri only
            @Override
            public int hashCode() {
                return mResult != null ? mResult.hashCode() : 0;
            }

            @Override
            public String toString() {
                return mResult.toString();
            }
        }

        // Provide a suitable constructor (depends on the kind of dataset)
        public DecryptFilesAdapter(OnMenuItemClickListener menuItemClickListener) {
            mMenuItemClickListener = menuItemClickListener;
            mDataset = new ArrayList<>();
        }

        // Create new views (invoked by the layout manager)
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            //inflate your layout and pass it to view holder
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.decrypt_list_entry, parent, false);
            return new ViewHolder(v);
        }

        // Replace the contents of a view (invoked by the layout manager)
        @Override
        public void onBindViewHolder(ViewHolder holder, final int position) {
            // - get element from your dataset at this position
            // - replace the contents of the view with that element
            final ViewModel model = mDataset.get(position);

            if (model.mCancelled != null) {
                bindItemCancelled(holder, model);
                return;
            }

            if (!model.hasResult()) {
                bindItemProgress(holder, model);
                return;
            }

            if (model.mResult.success()) {
                bindItemSuccess(holder, model);
            } else {
                bindItemFailure(holder, model);
            }

        }

        private void bindItemCancelled(ViewHolder holder, ViewModel model) {
            if (holder.vAnimator.getDisplayedChild() != 3) {
                holder.vAnimator.setDisplayedChild(3);
            }

            holder.vCancelledRetry.setOnClickListener(model.mCancelled);
        }

        private void bindItemProgress(ViewHolder holder, ViewModel model) {
            if (holder.vAnimator.getDisplayedChild() != 0) {
                holder.vAnimator.setDisplayedChild(0);
            }

            holder.vProgress.setProgress(model.mProgress);
            holder.vProgress.setMax(model.mMax);
            if (model.mProgressMsg != null) {
                holder.vProgressMsg.setText(model.mProgressMsg);
            }
        }

        private void bindItemSuccess(ViewHolder holder, final ViewModel model) {
            if (holder.vAnimator.getDisplayedChild() != 1) {
                holder.vAnimator.setDisplayedChild(1);
            }

            KeyFormattingUtils.setStatus(getResources(), holder, model.mResult.mDecryptVerifyResult);

            int numFiles = model.mResult.getOutputUris().size();
            holder.resizeFileList(numFiles, LayoutInflater.from(getActivity()));
            for (int i = 0; i < numFiles; i++) {

                Uri outputUri = model.mResult.getOutputUris().get(i);
                OpenPgpMetadata metadata = model.mResult.mMetadata.get(i);
                SubViewHolder fileHolder = holder.mFileHolderList.get(i);

                String filename;
                if (metadata == null) {
                    filename = getString(R.string.filename_unknown);
                } else if (TextUtils.isEmpty(metadata.getFilename())) {
                    filename = getString("text/plain".equals(metadata.getMimeType())
                            ? R.string.filename_unknown_text : R.string.filename_unknown);
                } else {
                    filename = metadata.getFilename();
                }
                fileHolder.vFilename.setText(filename);

                long size = metadata == null ? 0 : metadata.getOriginalSize();
                if (size == -1 || size == 0) {
                    fileHolder.vFilesize.setText("");
                } else {
                    fileHolder.vFilesize.setText(FileHelper.readableFileSize(size));
                }

                if (mIconCache.containsKey(outputUri)) {
                    fileHolder.vThumbnail.setImageDrawable(mIconCache.get(outputUri));
                } else {
                    fileHolder.vThumbnail.setImageResource(R.drawable.ic_doc_generic_am);
                }

                // save index closure-style :)
                final int idx = i;

                fileHolder.vFile.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View view) {
                        if (model.mResult.success()) {
                            displayBottomSheet(model.mResult, idx);
                            return true;
                        }
                        return false;
                    }
                });

                fileHolder.vFile.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (model.mResult.success()) {
                            displayWithViewIntent(model.mResult, idx, false);
                        }
                    }
                });

            }

            OpenPgpSignatureResult sigResult = model.mResult.mDecryptVerifyResult.getSignatureResult();
            if (sigResult != null) {
                final long keyId = sigResult.getKeyId();
                if (sigResult.getResult() != OpenPgpSignatureResult.RESULT_KEY_MISSING) {
                    holder.vSignatureLayout.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            Activity activity = getActivity();
                            if (activity == null) {
                                return;
                            }
                            Intent intent = new Intent(activity, ViewKeyActivity.class);
                            intent.setData(KeyRings.buildUnifiedKeyRingUri(keyId));
                            activity.startActivity(intent);
                        }
                    });
                }
            }

            holder.vContextMenu.setTag(model);
            holder.vContextMenu.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    Activity activity = getActivity();
                    if (activity == null) {
                        return;
                    }
                    mMenuClickedModel = model;
                    PopupMenu menu = new PopupMenu(activity, view);
                    menu.inflate(R.menu.decrypt_item_context_menu);
                    menu.setOnMenuItemClickListener(mMenuItemClickListener);
                    menu.setOnDismissListener(new OnDismissListener() {
                        @Override
                        public void onDismiss(PopupMenu popupMenu) {
                            mMenuClickedModel = null;
                        }
                    });
                    menu.show();
                }
            });
        }

        private void bindItemFailure(ViewHolder holder, final ViewModel model) {
            if (holder.vAnimator.getDisplayedChild() != 2) {
                holder.vAnimator.setDisplayedChild(2);
            }

            holder.vErrorMsg.setText(model.mResult.getLog().getLast().mType.getMsgId());

            holder.vErrorViewLog.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Activity activity = getActivity();
                    if (activity == null) {
                        return;
                    }
                    Intent intent = new Intent(activity, LogDisplayActivity.class);
                    intent.putExtra(LogDisplayFragment.EXTRA_RESULT, model.mResult);
                    activity.startActivity(intent);
                }
            });

        }

        // Return the size of your dataset (invoked by the layout manager)
        @Override
        public int getItemCount() {
            return mDataset.size();
        }

        public InputDataResult getItemResult(Uri uri) {
            ViewModel model = new ViewModel(uri);
            int pos = mDataset.indexOf(model);
            if (pos == -1) {
                return null;
            }
            model = mDataset.get(pos);

            return model.mResult;
        }

        public void add(Uri uri) {
            ViewModel newModel = new ViewModel(uri);
            mDataset.add(newModel);
            notifyItemInserted(mDataset.size());
        }

        public void setProgress(Uri uri, int progress, int max, String msg) {
            ViewModel newModel = new ViewModel(uri);
            int pos = mDataset.indexOf(newModel);
            mDataset.get(pos).setProgress(progress, max, msg);
            notifyItemChanged(pos);
        }

        public void setCancelled(Uri uri, OnClickListener retryListener) {
            ViewModel newModel = new ViewModel(uri);
            int pos = mDataset.indexOf(newModel);
            mDataset.get(pos).setCancelled(retryListener);
            notifyItemChanged(pos);
        }

        public void addResult(Uri uri, InputDataResult result) {

            ViewModel model = new ViewModel(uri);
            int pos = mDataset.indexOf(model);
            model = mDataset.get(pos);

            model.addResult(result);

            notifyItemChanged(pos);
        }

    }


    // Provide a reference to the views for each data item
    // Complex data items may need more than one view per item, and
    // you provide access to all the views for a data item in a view holder
    public static class ViewHolder extends RecyclerView.ViewHolder implements StatusHolder {
        public ViewAnimator vAnimator;

        public ProgressBar vProgress;
        public TextView vProgressMsg;

        public ImageView vEncStatusIcon;
        public TextView vEncStatusText;

        public ImageView vSigStatusIcon;
        public TextView vSigStatusText;
        public View vSignatureLayout;
        public TextView vSignatureName;
        public TextView vSignatureMail;
        public TextView vSignatureAction;
        public View vContextMenu;

        public TextView vErrorMsg;
        public ImageView vErrorViewLog;

        public ImageView vCancelledRetry;

        public LinearLayout vFileList;

        public static class SubViewHolder {
            public View vFile;
            public TextView vFilename;
            public TextView vFilesize;
            public ImageView vThumbnail;

            public SubViewHolder(View itemView) {
                vFile = itemView.findViewById(R.id.file);
                vFilename = (TextView) itemView.findViewById(R.id.filename);
                vFilesize = (TextView) itemView.findViewById(R.id.filesize);
                vThumbnail = (ImageView) itemView.findViewById(R.id.thumbnail);
            }
        }

        public ArrayList<SubViewHolder> mFileHolderList = new ArrayList<>();
        private int mCurrentFileListSize = 0;

        public ViewHolder(View itemView) {
            super(itemView);

            vAnimator = (ViewAnimator) itemView.findViewById(R.id.view_animator);

            vProgress = (ProgressBar) itemView.findViewById(R.id.progress);
            vProgressMsg = (TextView) itemView.findViewById(R.id.progress_msg);

            vEncStatusIcon = (ImageView) itemView.findViewById(R.id.result_encryption_icon);
            vEncStatusText = (TextView) itemView.findViewById(R.id.result_encryption_text);

            vSigStatusIcon = (ImageView) itemView.findViewById(R.id.result_signature_icon);
            vSigStatusText = (TextView) itemView.findViewById(R.id.result_signature_text);
            vSignatureLayout = itemView.findViewById(R.id.result_signature_layout);
            vSignatureName = (TextView) itemView.findViewById(R.id.result_signature_name);
            vSignatureMail= (TextView) itemView.findViewById(R.id.result_signature_email);
            vSignatureAction = (TextView) itemView.findViewById(R.id.result_signature_action);

            vFileList = (LinearLayout) itemView.findViewById(R.id.file_list);
            for (int i = 0; i < vFileList.getChildCount(); i++) {
                mFileHolderList.add(new SubViewHolder(vFileList.getChildAt(i)));
                mCurrentFileListSize += 1;
            }

            vContextMenu = itemView.findViewById(R.id.context_menu);

            vErrorMsg = (TextView) itemView.findViewById(R.id.result_error_msg);
            vErrorViewLog = (ImageView) itemView.findViewById(R.id.result_error_log);

            vCancelledRetry = (ImageView) itemView.findViewById(R.id.cancel_retry);

        }

        public void resizeFileList(int size, LayoutInflater inflater) {
            int childCount = vFileList.getChildCount();
            // if we require more children, create them
            while (childCount < size) {
                View v = inflater.inflate(R.layout.decrypt_list_file_item, null);
                vFileList.addView(v);
                mFileHolderList.add(new SubViewHolder(v));
                childCount += 1;
            }

            while (size < mCurrentFileListSize) {
                mCurrentFileListSize -= 1;
                vFileList.getChildAt(mCurrentFileListSize).setVisibility(View.GONE);
            }
            while (size > mCurrentFileListSize) {
                vFileList.getChildAt(mCurrentFileListSize).setVisibility(View.VISIBLE);
                mCurrentFileListSize += 1;
            }

        }

        @Override
        public ImageView getEncryptionStatusIcon() {
            return vEncStatusIcon;
        }

        @Override
        public TextView getEncryptionStatusText() {
            return vEncStatusText;
        }

        @Override
        public ImageView getSignatureStatusIcon() {
            return vSigStatusIcon;
        }

        @Override
        public TextView getSignatureStatusText() {
            return vSigStatusText;
        }

        @Override
        public View getSignatureLayout() {
            return vSignatureLayout;
        }

        @Override
        public TextView getSignatureAction() {
            return vSignatureAction;
        }

        @Override
        public TextView getSignatureUserName() {
            return vSignatureName;
        }

        @Override
        public TextView getSignatureUserEmail() {
            return vSignatureMail;
        }

        @Override
        public boolean hasEncrypt() {
            return true;
        }
    }

}
