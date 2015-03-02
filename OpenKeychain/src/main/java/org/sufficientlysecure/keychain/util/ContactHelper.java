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

package org.sufficientlysecure.keychain.util;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.TargetApi;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Patterns;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.KeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.UserPackets;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ContactHelper {

    public static final String[] KEYS_TO_CONTACT_PROJECTION = new String[]{
            KeychainContract.KeyRings.USER_ID,
            KeychainContract.KeyRings.FINGERPRINT,
            KeychainContract.KeyRings.KEY_ID,
            KeychainContract.KeyRings.MASTER_KEY_ID,
            KeychainContract.KeyRings.EXPIRY,
            KeychainContract.KeyRings.IS_REVOKED};

    public static final int INDEX_USER_ID = 0;
    public static final int INDEX_FINGERPRINT = 1;
    public static final int INDEX_KEY_ID = 2;
    public static final int INDEX_MASTER_KEY_ID = 3;
    public static final int INDEX_EXPIRY = 4;
    public static final int INDEX_IS_REVOKED = 5;

    public static final String[] USER_IDS_PROJECTION = new String[]{
            UserPackets.USER_ID
    };

    public static final int INDEX_USER_IDS_USER_ID = 0;

    public static final String NON_REVOKED_SELECTION = UserPackets.IS_REVOKED + "=0";

    public static final String[] ID_PROJECTION = new String[]{ContactsContract.RawContacts._ID};
    public static final String[] SOURCE_ID_PROJECTION = new String[]{ContactsContract.RawContacts.SOURCE_ID};

    public static final String ACCOUNT_TYPE_AND_SOURCE_ID_SELECTION =
            ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND " + ContactsContract.RawContacts.SOURCE_ID + "=?";
    public static final String ACCOUNT_TYPE_SELECTION = ContactsContract.RawContacts.ACCOUNT_TYPE + "=?";
    public static final String RAW_CONTACT_AND_MIMETYPE_SELECTION =
            ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?";
    public static final String ID_SELECTION = ContactsContract.RawContacts._ID + "=?";

    private static final Map<String, Bitmap> photoCache = new HashMap<>();

    public static List<String> getPossibleUserEmails(Context context) {
        Set<String> accountMails = getAccountEmails(context);
        accountMails.addAll(getMainProfileContactEmails(context));
        // now return the Set (without duplicates) as a List
        return new ArrayList<>(accountMails);
    }

    public static List<String> getPossibleUserNames(Context context) {
        Set<String> accountMails = getAccountEmails(context);
        Set<String> names = getContactNamesFromEmails(context, accountMails);
        names.addAll(getMainProfileContactName(context));
        return new ArrayList<>(names);
    }

    /**
     * Get emails from AccountManager
     *
     * @param context
     * @return
     */
    private static Set<String> getAccountEmails(Context context) {
        final Account[] accounts = AccountManager.get(context).getAccounts();
        final Set<String> emailSet = new HashSet<>();
        for (Account account : accounts) {
            if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                emailSet.add(account.name);
            }
        }
        return emailSet;
    }

    /**
     * Search for contact names based on a list of emails (to find out the names of the
     * device owner based on the email addresses from AccountsManager)
     *
     * @param context
     * @param emails
     * @return
     */
    private static Set<String> getContactNamesFromEmails(Context context, Set<String> emails) {
        Set<String> names = new HashSet<>();
        for (String email : emails) {
            ContentResolver resolver = context.getContentResolver();
            Cursor profileCursor = resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Email.ADDRESS,
                            ContactsContract.Contacts.DISPLAY_NAME
                    },
                    ContactsContract.CommonDataKinds.Email.ADDRESS + "=?",
                    new String[]{email}, null
            );
            if (profileCursor == null) {
                return null;
            }

            Set<String> currNames = new HashSet<>();
            while (profileCursor.moveToNext()) {
                String name = profileCursor.getString(1);
                if (name != null) {
                    currNames.add(name);
                }
            }
            profileCursor.close();
            names.addAll(currNames);
        }
        return names;
    }

    /**
     * Retrieves the emails of the primary profile contact
     * http://developer.android.com/reference/android/provider/ContactsContract.Profile.html
     *
     * @param context
     * @return
     */
    private static Set<String> getMainProfileContactEmails(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Cursor profileCursor = resolver.query(
                Uri.withAppendedPath(
                        ContactsContract.Profile.CONTENT_URI,
                        ContactsContract.Contacts.Data.CONTENT_DIRECTORY),
                new String[]{
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.IS_PRIMARY
                },
                // Selects only email addresses
                ContactsContract.Contacts.Data.MIMETYPE + "=?",
                new String[]{
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                },
                // Show primary rows first. Note that there won't be a primary email address if the
                // user hasn't specified one.
                ContactsContract.Contacts.Data.IS_PRIMARY + " DESC"
        );
        if (profileCursor == null) {
            return null;
        }

        Set<String> emails = new HashSet<>();
        while (profileCursor.moveToNext()) {
            String email = profileCursor.getString(0);
            if (email != null) {
                emails.add(email);
            }
        }
        profileCursor.close();
        return emails;
    }

    /**
     * Retrieves the name of the primary profile contact
     * http://developer.android.com/reference/android/provider/ContactsContract.Profile.html
     *
     * @param context
     * @return
     */
    private static List<String> getMainProfileContactName(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Cursor profileCursor = resolver.query(
                ContactsContract.Profile.CONTENT_URI,
                new String[]{
                        ContactsContract.Profile.DISPLAY_NAME
                },
                null, null, null);
        if (profileCursor == null) {
            return null;
        }

        Set<String> names = new HashSet<>();
        // should only contain one entry!
        while (profileCursor.moveToNext()) {
            String name = profileCursor.getString(0);
            if (name != null) {
                names.add(name);
            }
        }
        profileCursor.close();
        return new ArrayList<>(names);
    }

    public static List<String> getContactMails(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Cursor mailCursor = resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Email.DATA},
                null, null, null);
        if (mailCursor == null) {
            return new ArrayList<>();
        }

        Set<String> mails = new HashSet<>();
        while (mailCursor.moveToNext()) {
            String email = mailCursor.getString(0);
            if (email != null) {
                mails.add(email);
            }
        }
        mailCursor.close();
        return new ArrayList<>(mails);
    }

    public static List<String> getContactNames(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = resolver.query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME},
                null, null, null);
        if (cursor == null) {
            return new ArrayList<>();
        }

        Set<String> names = new HashSet<>();
        while (cursor.moveToNext()) {
            String name = cursor.getString(0);
            if (name != null) {
                names.add(name);
            }
        }
        cursor.close();
        return new ArrayList<>(names);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public static Uri dataUriFromContactUri(Context context, Uri contactUri) {
        Cursor contactMasterKey = context.getContentResolver().query(contactUri,
                new String[]{ContactsContract.Data.DATA2}, null, null, null, null);
        if (contactMasterKey != null) {
            if (contactMasterKey.moveToNext()) {
                return KeychainContract.KeyRings.buildGenericKeyRingUri(contactMasterKey.getLong(0));
            }
            contactMasterKey.close();
        }
        return null;
    }

    public static Bitmap photoFromFingerprint(ContentResolver contentResolver, String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        if (!photoCache.containsKey(fingerprint)) {
            photoCache.put(fingerprint, loadPhotoFromFingerprint(contentResolver, fingerprint));
        }
        return photoCache.get(fingerprint);
    }

    private static Bitmap loadPhotoFromFingerprint(ContentResolver contentResolver, String fingerprint) {
        if (fingerprint == null) return null;
        try {
            int rawContactId = findRawContactId(contentResolver, fingerprint);
            if (rawContactId == -1) {
                return null;
            }
            Uri rawContactUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId);
            Uri contactUri = ContactsContract.RawContacts.getContactLookupUri(contentResolver, rawContactUri);
            InputStream photoInputStream =
                    ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, contactUri);
            if (photoInputStream == null) {
                return null;
            }
            return BitmapFactory.decodeStream(photoInputStream);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Write the current Keychain to the contact db
     */
    public static void writeKeysToContacts(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Set<String> deletedKeys = getRawContactFingerprints(resolver);

//        ContentProviderClient client = resolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
//        ContentValues values = new ContentValues();
//        Account account = new Account(Constants.ACCOUNT_NAME, Constants.ACCOUNT_TYPE);
//        values.put(ContactsContract.Settings.ACCOUNT_NAME, account.name);
//        values.put(ContactsContract.Settings.ACCOUNT_TYPE, account.type);
//        values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, true);
//        try {
//            client.insert(ContactsContract.Settings.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), values);
//        } catch (RemoteException e) {
//            e.printStackTrace();
//        }

        // Load all Keys from OK
        Cursor cursor = resolver.query(KeychainContract.KeyRings.buildUnifiedKeyRingsUri(), KEYS_TO_CONTACT_PROJECTION,
                null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String[] primaryUserId = KeyRing.splitUserId(cursor.getString(INDEX_USER_ID));
                String fingerprint = KeyFormattingUtils.convertFingerprintToHex(cursor.getBlob(INDEX_FINGERPRINT));
                deletedKeys.remove(fingerprint);

                Log.d(Constants.TAG, "fingerprint: " + fingerprint);

                String keyIdShort = KeyFormattingUtils.convertKeyIdToHexShort(cursor.getLong(INDEX_KEY_ID));
                long masterKeyId = cursor.getLong(INDEX_MASTER_KEY_ID);
                boolean isExpired = !cursor.isNull(INDEX_EXPIRY)
                        && new Date(cursor.getLong(INDEX_EXPIRY) * 1000).before(new Date());
                boolean isRevoked = cursor.getInt(INDEX_IS_REVOKED) > 0;
                int rawContactId = findRawContactId(resolver, fingerprint);
                ArrayList<ContentProviderOperation> ops = new ArrayList<>();

                Log.d(Constants.TAG, "raw contact id: " + rawContactId);

                // Do not store expired or revoked keys in contact db - and remove them if they already exist
                if (isExpired || isRevoked) {
                    Log.d(Constants.TAG, "Expired or revoked: Deleting " + rawContactId);
                    if (rawContactId != -1) {
                        resolver.delete(ContactsContract.RawContacts.CONTENT_URI, ID_SELECTION,
                                new String[]{Integer.toString(rawContactId)});
                    }
                } else if (primaryUserId[0] != null) {

                    // Create a new rawcontact with corresponding key if it does not exist yet
                    if (rawContactId == -1) {
                        Log.d(Constants.TAG, "Insert new raw contact with fingerprint " + fingerprint);

                        insertContact(ops, context, fingerprint);
                        writeContactKey(ops, context, rawContactId, masterKeyId, keyIdShort);
                    }

                    // We always update the display name (which is derived from primary user id)
                    // and email addresses from user id
                    writeContactDisplayName(ops, rawContactId, primaryUserId[0]);
                    writeContactEmail(ops, resolver, rawContactId, masterKeyId);
                    try {
                        resolver.applyBatch(ContactsContract.AUTHORITY, ops);
                    } catch (Exception e) {
                        Log.w(Constants.TAG, e);
                    }
                }
            }
            cursor.close();
        }

        // Delete fingerprints that are no longer present in OK
        for (String fingerprint : deletedKeys) {
            resolver.delete(ContactsContract.RawContacts.CONTENT_URI, ACCOUNT_TYPE_AND_SOURCE_ID_SELECTION,
                    new String[]{Constants.ACCOUNT_TYPE, fingerprint});
        }
    }

    /**
     * @return a set of all key fingerprints currently present in the contact db
     */
    private static Set<String> getRawContactFingerprints(ContentResolver resolver) {
        HashSet<String> result = new HashSet<>();
        Cursor fingerprints = resolver.query(ContactsContract.RawContacts.CONTENT_URI, SOURCE_ID_PROJECTION,
                ACCOUNT_TYPE_SELECTION, new String[]{Constants.ACCOUNT_TYPE}, null);
        if (fingerprints != null) {
            while (fingerprints.moveToNext()) {
                result.add(fingerprints.getString(0));
            }
            fingerprints.close();
        }
        return result;
    }

    /**
     * This will search the contact db for a raw contact with a given fingerprint
     *
     * @return raw contact id or -1 if not found
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private static int findRawContactId(ContentResolver resolver, String fingerprint) {
        int rawContactId = -1;
        Cursor raw = resolver.query(ContactsContract.RawContacts.CONTENT_URI, ID_PROJECTION,
                ACCOUNT_TYPE_AND_SOURCE_ID_SELECTION, new String[]{Constants.ACCOUNT_TYPE, fingerprint}, null, null);
        if (raw != null) {
            if (raw.moveToNext()) {
                rawContactId = raw.getInt(0);
            }
            raw.close();
        }
        return rawContactId;
    }

    /**
     * Creates a empty raw contact with a given fingerprint
     */
    private static void insertContact(ArrayList<ContentProviderOperation> ops, Context context, String fingerprint) {
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, Constants.ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, fingerprint)
                .build());
    }

    /**
     * Adds a key id to the given raw contact.
     * <p/>
     * This creates the link to OK in contact details
     */
    private static void writeContactKey(ArrayList<ContentProviderOperation> ops, Context context, int rawContactId,
                                        long masterKeyId, String keyIdShort) {
        ops.add(referenceRawContact(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI), rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, Constants.CUSTOM_CONTACT_DATA_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, context.getString(R.string.contact_show_key, keyIdShort))
                .withValue(ContactsContract.Data.DATA2, masterKeyId)
                .build());
    }

    /**
     * Write all known email addresses of a key (derived from user ids) to a given raw contact
     */
    private static void writeContactEmail(ArrayList<ContentProviderOperation> ops, ContentResolver resolver,
                                          int rawContactId, long masterKeyId) {
        ops.add(selectByRawContactAndItemType(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI),
                rawContactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE).build());
        Cursor ids = resolver.query(UserPackets.buildUserIdsUri(masterKeyId),
                USER_IDS_PROJECTION, NON_REVOKED_SELECTION, null, null);
        if (ids != null) {
            while (ids.moveToNext()) {
                String[] userId = KeyRing.splitUserId(ids.getString(0));
                if (userId[1] != null) {
                    ops.add(referenceRawContact(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI),
                            rawContactId)
                            .withValue(ContactsContract.Data.MIMETYPE,
                                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.DATA, userId[1])
                            .build());
                }
            }
            ids.close();
        }
    }

    private static void writeContactDisplayName(ArrayList<ContentProviderOperation> ops, int rawContactId,
                                                String displayName) {
        if (displayName != null) {
            ops.add(insertOrUpdateForRawContact(ContactsContract.Data.CONTENT_URI, rawContactId,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build());
        }
    }

    private static ContentProviderOperation.Builder referenceRawContact(ContentProviderOperation.Builder builder,
                                                                        int rawContactId) {
        return rawContactId == -1 ?
                builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0) :
                builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
    }

    private static ContentProviderOperation.Builder insertOrUpdateForRawContact(Uri uri, int rawContactId,
                                                                                String itemType) {
        if (rawContactId == -1) {
            return referenceRawContact(ContentProviderOperation.newInsert(uri), rawContactId).withValue(
                    ContactsContract.Data.MIMETYPE, itemType);
        } else {
            return selectByRawContactAndItemType(ContentProviderOperation.newUpdate(uri), rawContactId, itemType);
        }
    }

    private static ContentProviderOperation.Builder selectByRawContactAndItemType(
            ContentProviderOperation.Builder builder, int rawContactId, String itemType) {
        return builder.withSelection(RAW_CONTACT_AND_MIMETYPE_SELECTION,
                new String[]{Integer.toString(rawContactId), itemType});
    }
}
