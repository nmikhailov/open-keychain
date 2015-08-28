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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.support.annotation.StringRes;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.widget.Toast;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.compatibility.DialogFragmentWorkaround;
import org.sufficientlysecure.keychain.ui.dialog.FileDialogFragment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;


/** This class offers a number of helper functions for saving documents.
 *
 * There are three entry points here: openDocument, saveDocument and
 * saveDocumentDialog. Each behaves a little differently depending on whether
 * the Android version used is pre or post KitKat.
 *
 * - openDocument queries for a document for reading. Used in "open encrypted
 *   file" ui flow. On pre-kitkat, this relies on an external file manager,
 *   and will fail with a toast message if none is installed.
 *
 * - saveDocument queries for a document name for saving. on pre-kitkat, this
 *   shows a dialog where a filename can be input.  on kitkat and up, it
 *   directly triggers a "save document" intent. Used in "save encrypted file"
 *   ui flow.
 *
 * - saveDocumentDialog queries for a document. this shows a dialog on all
 *   versions of android. the browse button opens an external browser on
 *   pre-kitkat or the "save document" intent on post-kitkat devices. Used in
 *   "backup key" ui flow.
 *
 *   It is noteworthy that the "saveDocument" call is essentially substituted
 *   by the "saveDocumentDialog" on pre-kitkat devices.
 *
 */
public class FileHelper {

    public static void openDocument(Fragment fragment, Uri last, String mimeType, boolean multiple, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            openDocumentPreKitKat(fragment, last, mimeType, multiple, requestCode);
        } else {
            openDocumentKitKat(fragment, mimeType, multiple, requestCode);
        }
    }

    public static void saveDocument(Fragment fragment, String targetName, Uri inputUri,
            @StringRes int title, @StringRes int message, int requestCode) {
        saveDocument(fragment, targetName, inputUri, "*/*", title, message, requestCode);
    }

    public static void saveDocument(Fragment fragment, String targetName, Uri inputUri, String mimeType,
            @StringRes int title, @StringRes int message, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            saveDocumentDialog(fragment, targetName, inputUri, title, message, requestCode);
        } else {
            saveDocumentKitKat(fragment, mimeType, targetName, requestCode);
        }
    }

    public static void saveDocumentDialog(final Fragment fragment, String targetName, Uri inputUri,
            @StringRes int title, @StringRes int message, final int requestCode) {

        saveDocumentDialog(fragment, targetName, inputUri, title, message, new FileDialogCallback() {
            // is this a good idea? seems hacky...
            @Override
            public void onFileSelected(File file, boolean checked) {
                Intent intent = new Intent();
                intent.setData(Uri.fromFile(file));
                fragment.onActivityResult(requestCode, Activity.RESULT_OK, intent);
            }
        });
    }

    public static void saveDocumentDialog(final Fragment fragment, String targetName, Uri inputUri,
            @StringRes int title, @StringRes int message, FileDialogCallback callback) {

        File file = inputUri == null ? null : new File(inputUri.getPath());
        File parentDir = file != null && file.exists() ? file.getParentFile() : Constants.Path.APP_DIR;
        File targetFile = new File(parentDir, targetName);
        saveDocumentDialog(callback, fragment.getActivity().getSupportFragmentManager(),
                fragment.getString(title), fragment.getString(message), targetFile, null);

    }

    /** Opens the preferred installed file manager on Android and shows a toast
     * if no manager is installed. */
    private static void openDocumentPreKitKat(
            Fragment fragment, Uri last, String mimeType, boolean multiple, int requestCode) {

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (Build.VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN_MR2) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        }
        intent.setData(last);
        intent.setType(mimeType);

        try {
            fragment.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            // No compatible file manager was found.
            Toast.makeText(fragment.getActivity(), R.string.no_filemanager_installed,
                    Toast.LENGTH_SHORT).show();
        }

    }

    /** Opens the storage browser on Android 4.4 or later for opening a file */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static void openDocumentKitKat(Fragment fragment, String mimeType, boolean multiple, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        fragment.startActivityForResult(intent, requestCode);
    }

    /** Opens the storage browser on Android 4.4 or later for saving a file. */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static void saveDocumentKitKat(Fragment fragment, String mimeType, String suggestedName, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true); // Note: This is not documented, but works
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        fragment.startActivityForResult(intent, requestCode);
    }

    public static void saveDocumentDialog(
            final FileDialogCallback callback, final FragmentManager fragmentManager,
            final String title, final String message, final File defaultFile,
            final String checkMsg) {
        // Message is received after file is selected
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == FileDialogFragment.MESSAGE_OKAY) {
                    callback.onFileSelected(
                            new File(message.getData().getString(FileDialogFragment.MESSAGE_DATA_FILE)),
                            message.getData().getBoolean(FileDialogFragment.MESSAGE_DATA_CHECKED));
                }
            }
        };

        // Create a new Messenger for the communication back
        final Messenger messenger = new Messenger(returnHandler);

        DialogFragmentWorkaround.INTERFACE.runnableRunDelayed(new Runnable() {
            @Override
            public void run() {
                FileDialogFragment fileDialog = FileDialogFragment.newInstance(messenger, title, message,
                        defaultFile, checkMsg);

                fileDialog.show(fragmentManager, "fileDialog");
            }
        });
    }

    public static String getFilename(Context context, Uri uri) {
        String filename = null;
        try {
            Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);

            if (cursor != null) {
                if (cursor.moveToNext()) {
                    filename = cursor.getString(0);
                }
                cursor.close();
            }
        } catch (Exception ignored) {
            // This happens in rare cases (eg: document deleted since selection) and should not cause a failure
        }
        if (filename == null) {
            String[] split = uri.toString().split("/");
            filename = split[split.length - 1];
        }
        return filename;
    }

    public static long getFileSize(Context context, Uri uri) {
        return getFileSize(context, uri, -1);
    }

    public static long getFileSize(Context context, Uri uri, long def) {
        long size = def;
        try {
            Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);

            if (cursor != null) {
                if (cursor.moveToNext()) {
                    size = cursor.getLong(0);
                }
                cursor.close();
            }
        } catch (Exception ignored) {
            // This happens in rare cases (eg: document deleted since selection) and should not cause a failure
        }
        return size;
    }

    /**
     * Retrieve thumbnail of file, document api feature and thus KitKat only
     */
    public static Bitmap getThumbnail(Context context, Uri uri, Point size) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return DocumentsContract.getDocumentThumbnail(context.getContentResolver(), uri, size, null);
        } else {
            return null;
        }
    }

    public static String readableFileSize(long size) {
        if (size <= 0) return "0";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    public static String readTextFromUri(Context context, Uri outputUri, String charset)
        throws IOException {

        byte[] decryptedMessage;
        {
            InputStream in = context.getContentResolver().openInputStream(outputUri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int read;
            while ( (read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            in.close();
            out.close();
            decryptedMessage = out.toByteArray();
        }

        String plaintext;
        if (charset != null) {
            try {
                plaintext = new String(decryptedMessage, charset);
            } catch (UnsupportedEncodingException e) {
                // if we can't decode properly, just fall back to utf-8
                plaintext = new String(decryptedMessage);
            }
        } else {
            plaintext = new String(decryptedMessage);
        }

        return plaintext;

    }

    public static void copyUriData(Context context, Uri fromUri, Uri toUri) throws IOException {
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;

        try {
            ContentResolver resolver = context.getContentResolver();
            bis = new BufferedInputStream(resolver.openInputStream(fromUri));
            bos = new BufferedOutputStream(resolver.openOutputStream(toUri));
            byte[] buf = new byte[1024];
            int len;
            while ( (len = bis.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
        } finally {
            try {
                if (bis != null) {
                    bis.close();
                }
                if (bos != null) {
                    bos.close();
                }
            } catch (IOException e) {
                // ignore, it's just stream closin'
            }
        }
    }

    /** Checks if external storage is mounted if file is located on external storage. */
    public static boolean isStorageMounted(String file) {
        if (file.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                return false;
            }
        }

        return true;
    }

    public interface FileDialogCallback {
        void onFileSelected(File file, boolean checked);
    }
}
