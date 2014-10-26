/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010-2014 Thialfihar <thi@thialfihar.org>
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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.spongycastle.bcpg.CompressionAlgorithmTags;
import org.spongycastle.bcpg.HashAlgorithmTags;
import org.spongycastle.openpgp.PGPEncryptedData;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.Constants.Pref;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Vector;

/**
 * Singleton Implementation of a Preference Helper
 */
public class Preferences {
    private static Preferences sPreferences;
    private SharedPreferences mSharedPreferences;

    public static synchronized Preferences getPreferences(Context context) {
        return getPreferences(context, false);
    }

    public static synchronized Preferences getPreferences(Context context, boolean forceNew) {
        if (sPreferences == null || forceNew) {
            sPreferences = new Preferences(context);
        } else {
            // to make it safe for multiple processes, call getSharedPreferences everytime
            sPreferences.updateSharedPreferences(context);
        }
        return sPreferences;
    }

    private Preferences(Context context) {
        updateSharedPreferences(context);
    }

    public void updateSharedPreferences(Context context) {
        // multi-process preferences
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            mSharedPreferences = context.getSharedPreferences("APG.main", Context.MODE_MULTI_PROCESS);
        } else {
            mSharedPreferences = context.getSharedPreferences("APG.main", Context.MODE_PRIVATE);
        }
    }

    public String getLanguage() {
        return mSharedPreferences.getString(Constants.Pref.LANGUAGE, "");
    }

    public void setLanguage(String value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(Constants.Pref.LANGUAGE, value);
        editor.commit();
    }

    public long getPassphraseCacheTtl() {
        int ttl = mSharedPreferences.getInt(Constants.Pref.PASSPHRASE_CACHE_TTL, 180);
        // fix the value if it was set to "never" in previous versions, which currently is not
        // supported
        if (ttl == 0) {
            ttl = 180;
        }
        return (long) ttl;
    }

    public void setPassphraseCacheTtl(int value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(Constants.Pref.PASSPHRASE_CACHE_TTL, value);
        editor.commit();
    }

    public boolean getPassphraseCacheSubs() {
        return mSharedPreferences.getBoolean(Pref.PASSPHRASE_CACHE_SUBS, false);
    }

    public void setPassphraseCacheSubs(boolean value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Pref.PASSPHRASE_CACHE_SUBS, value);
        editor.commit();
    }

    public int getDefaultEncryptionAlgorithm() {
        return mSharedPreferences.getInt(Constants.Pref.DEFAULT_ENCRYPTION_ALGORITHM,
                PGPEncryptedData.AES_256);
    }

    public void setDefaultEncryptionAlgorithm(int value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(Constants.Pref.DEFAULT_ENCRYPTION_ALGORITHM, value);
        editor.commit();
    }

    public int getDefaultHashAlgorithm() {
        return mSharedPreferences.getInt(Constants.Pref.DEFAULT_HASH_ALGORITHM,
                HashAlgorithmTags.SHA256);
    }

    public void setDefaultHashAlgorithm(int value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(Constants.Pref.DEFAULT_HASH_ALGORITHM, value);
        editor.commit();
    }

    public int getDefaultMessageCompression() {
        return mSharedPreferences.getInt(Constants.Pref.DEFAULT_MESSAGE_COMPRESSION,
                CompressionAlgorithmTags.ZLIB);
    }

    public void setDefaultMessageCompression(int value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(Constants.Pref.DEFAULT_MESSAGE_COMPRESSION, value);
        editor.commit();
    }

    public int getDefaultFileCompression() {
        return mSharedPreferences.getInt(Constants.Pref.DEFAULT_FILE_COMPRESSION,
                CompressionAlgorithmTags.UNCOMPRESSED);
    }

    public void setDefaultFileCompression(int value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(Constants.Pref.DEFAULT_FILE_COMPRESSION, value);
        editor.commit();
    }

    public boolean getDefaultAsciiArmor() {
        return mSharedPreferences.getBoolean(Constants.Pref.DEFAULT_ASCII_ARMOR, false);
    }

    public void setDefaultAsciiArmor(boolean value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Constants.Pref.DEFAULT_ASCII_ARMOR, value);
        editor.commit();
    }

    public boolean getCachedConsolidate() {
        return mSharedPreferences.getBoolean(Pref.CACHED_CONSOLIDATE, false);
    }

    public void setCachedConsolidate(boolean value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Pref.CACHED_CONSOLIDATE, value);
        editor.commit();
    }

    public boolean isFirstTime() {
        return mSharedPreferences.getBoolean(Constants.Pref.FIRST_TIME, true);
    }

    public boolean useDefaultYubikeyPin() {
        return mSharedPreferences.getBoolean(Pref.USE_DEFAULT_YUBIKEY_PIN, true);
    }

    public void setUseDefaultYubikeyPin(boolean useDefaultYubikeyPin) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Pref.USE_DEFAULT_YUBIKEY_PIN, useDefaultYubikeyPin);
        editor.commit();
    }

    public boolean useNumKeypadForYubikeyPin() {
        return mSharedPreferences.getBoolean(Pref.USE_NUMKEYPAD_FOR_YUBIKEY_PIN, false);
    }

    public void setUseNumKeypadForYubikeyPin(boolean useNumKeypadForYubikeyPin) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Pref.USE_NUMKEYPAD_FOR_YUBIKEY_PIN, useNumKeypadForYubikeyPin);
        editor.commit();
    }

    public void setFirstTime(boolean value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Constants.Pref.FIRST_TIME, value);
        editor.commit();
    }

    public String[] getKeyServers() {
        String rawData = mSharedPreferences.getString(Constants.Pref.KEY_SERVERS,
                Constants.Defaults.KEY_SERVERS);
        Vector<String> servers = new Vector<String>();
        String chunks[] = rawData.split(",");
        for (String c : chunks) {
            String tmp = c.trim();
            if (tmp.length() > 0) {
                servers.add(tmp);
            }
        }
        return servers.toArray(chunks);
    }
    public String getPreferredKeyserver() {
        return getKeyServers()[0];
    }

    public void setKeyServers(String[] value) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        String rawData = "";
        for (String v : value) {
            String tmp = v.trim();
            if (tmp.length() == 0) {
                continue;
            }
            if (!"".equals(rawData)) {
                rawData += ",";
            }
            rawData += tmp;
        }
        editor.putString(Constants.Pref.KEY_SERVERS, rawData);
        editor.commit();
    }

    public void setWriteVersionHeader(boolean conceal) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Constants.Pref.WRITE_VERSION_HEADER, conceal);
        editor.commit();
    }

    public boolean getWriteVersionHeader() {
        return mSharedPreferences.getBoolean(Constants.Pref.WRITE_VERSION_HEADER, false);
    }

    public void setSearchKeyserver(boolean searchKeyserver) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Pref.SEARCH_KEYSERVER, searchKeyserver);
        editor.commit();
    }
    public void setSearchKeybase(boolean searchKeybase) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(Pref.SEARCH_KEYBASE, searchKeybase);
        editor.commit();
    }

    public CloudSearchPrefs getCloudSearchPrefs() {
            return new CloudSearchPrefs(mSharedPreferences.getBoolean(Pref.SEARCH_KEYSERVER, true),
                mSharedPreferences.getBoolean(Pref.SEARCH_KEYBASE, true),
                getPreferredKeyserver());
    }

    public static class CloudSearchPrefs {
        public final boolean searchKeyserver;
        public final boolean searchKeybase;
        public final String keyserver;

        public CloudSearchPrefs(boolean searchKeyserver, boolean searchKeybase, String keyserver) {
            this.searchKeyserver = searchKeyserver;
            this.searchKeybase = searchKeybase;
            this.keyserver = keyserver;
        }
    }

    public void updatePreferences() {
        if (mSharedPreferences.getInt(Constants.Pref.PREF_DEFAULT_VERSION, 0) !=
                Constants.Defaults.PREF_VERSION) {
            switch (mSharedPreferences.getInt(Constants.Pref.PREF_DEFAULT_VERSION, 0)) {
                case 1:
                    // fall through
                case 2:
                    // fall through
                case 3: {
                    // migrate keyserver to hkps
                    String[] serversArray = getKeyServers();
                    ArrayList<String> servers = new ArrayList<String>(Arrays.asList(serversArray));
                    ListIterator<String> it = servers.listIterator();
                    while (it.hasNext()) {
                        String server = it.next();
                        if (server == null) {
                            continue;
                        }
                        if (server.equals("pool.sks-keyservers.net")) {
                            // use HKPS!
                            it.set("hkps://hkps.pool.sks-keyservers.net");
                        } else if (server.equals("pgp.mit.edu")) {
                            // use HKPS!
                            it.set("hkps://pgp.mit.edu");
                        } else if (server.equals("subkeys.pgp.net")) {
                            // remove, because often down and no HKPS!
                            it.remove();
                        }

                    }
                    setKeyServers(servers.toArray(new String[servers.size()]));

                    // migrate old uncompressed constant to new one
                    if (mSharedPreferences.getInt(Constants.Pref.DEFAULT_FILE_COMPRESSION, 0)
                            == 0x21070001) {
                        setDefaultFileCompression(CompressionAlgorithmTags.UNCOMPRESSED);
                    }

                    // migrate away from MD5
                    if (mSharedPreferences.getInt(Constants.Pref.DEFAULT_HASH_ALGORITHM, 0)
                            == HashAlgorithmTags.MD5) {
                        setDefaultHashAlgorithm(HashAlgorithmTags.SHA256);
                    }
                }
                // fall through
                case 4: {
                    // for compatibility: change from SHA512 to SHA256
                    if (mSharedPreferences.getInt(Constants.Pref.DEFAULT_HASH_ALGORITHM, 0)
                            == HashAlgorithmTags.SHA512) {
                        setDefaultHashAlgorithm(HashAlgorithmTags.SHA256);
                    }
                }
            }

            // write new preference version
            mSharedPreferences.edit()
                    .putInt(Constants.Pref.PREF_DEFAULT_VERSION, Constants.Defaults.PREF_VERSION)
                    .commit();
        }
    }
}
