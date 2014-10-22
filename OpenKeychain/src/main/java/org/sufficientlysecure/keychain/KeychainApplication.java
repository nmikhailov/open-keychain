/*
 * Copyright (C) 2012-2013 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.sufficientlysecure.keychain;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.provider.ContactsContract;
import android.widget.Toast;

import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.sufficientlysecure.keychain.util.Preferences;
import org.sufficientlysecure.keychain.util.TlsHelper;
import org.sufficientlysecure.keychain.provider.TemporaryStorageProvider;
import org.sufficientlysecure.keychain.ui.ConsolidateDialogActivity;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.PRNGFixes;

import java.security.Security;

public class KeychainApplication extends Application {

    /**
     * Called when the application is starting, before any activity, service, or receiver objects
     * (excluding content providers) have been created.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        /*
         * Sets Bouncy (Spongy) Castle as preferred security provider
         *
         * insertProviderAt() position starts from 1
         */
        Security.insertProviderAt(new BouncyCastleProvider(), 1);

        /*
         * apply RNG fixes
         *
         * among other things, executes Security.insertProviderAt(new
         * LinuxPRNGSecureRandomProvider(), 1) for Android <= SDK 17
         */
        PRNGFixes.apply();
        Log.d(Constants.TAG, "Bouncy Castle set and PRNG Fixes applied!");

        /*
        if (Constants.DEBUG) {
            Provider[] providers = Security.getProviders();
            Log.d(Constants.TAG, "Installed Security Providers:");
            for (Provider p : providers) {
                Log.d(Constants.TAG, "provider class: " + p.getClass().getName());
            }
        }
        */

        // Create APG directory on sdcard if not existing
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            if (!Constants.Path.APP_DIR.exists() && !Constants.Path.APP_DIR.mkdirs()) {
                // ignore this for now, it's not crucial
                // that the directory doesn't exist at this point
            }
        }

        brandGlowEffect(getApplicationContext(),
                getApplicationContext().getResources().getColor(R.color.emphasis));

        setupAccountAsNeeded(this);

        // Update keyserver list as needed
        Preferences.getPreferences(this).updatePreferences();

        TlsHelper.addStaticCA("pool.sks-keyservers.net", getAssets(), "sks-keyservers.netCA.cer");

        TemporaryStorageProvider.cleanUp(this);

        checkConsolidateRecovery();
    }

    /**
     * Restart consolidate process if it has been interruped before
     */
    public void checkConsolidateRecovery() {
        if (Preferences.getPreferences(this).getCachedConsolidate()) {
            Intent consolidateIntent = new Intent(this, ConsolidateDialogActivity.class);
            consolidateIntent.putExtra(ConsolidateDialogActivity.EXTRA_CONSOLIDATE_RECOVERY, true);
            consolidateIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(consolidateIntent);
        }
    }

    /**
     * Add OpenKeychain account to Android to link contacts with keys
     *
     * @param context
     */
    public static void setupAccountAsNeeded(Context context) {
        // only enabled for Jelly Bean because we need some newer methods in our sync adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                AccountManager manager = AccountManager.get(context);
                Account[] accounts = manager.getAccountsByType(Constants.ACCOUNT_TYPE);
                if (accounts == null || accounts.length == 0) {
                    Account account = new Account(Constants.ACCOUNT_NAME, Constants.ACCOUNT_TYPE);
                    if (manager.addAccountExplicitly(account, null, null)) {
                        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);
                        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true);
                    } else {
                        Log.e(Constants.TAG, "Adding account failed!");
                    }
                }
            } catch (SecurityException e) {
                Log.e(Constants.TAG, "SecurityException when adding the account", e);
                Toast.makeText(context, R.string.reinstall_openkeychain, Toast.LENGTH_LONG).show();
            }
        }
    }

    static void brandGlowEffect(Context context, int brandColor) {
        try {
            // terrible hack to brand the edge overscroll glow effect
            // https://gist.github.com/menny/7878762#file-brandgloweffect_full-java

            //glow
            int glowDrawableId = context.getResources().getIdentifier("overscroll_glow", "drawable", "android");
            Drawable androidGlow = context.getResources().getDrawable(glowDrawableId);
            androidGlow.setColorFilter(brandColor, PorterDuff.Mode.SRC_IN);
            //edge
            int edgeDrawableId = context.getResources().getIdentifier("overscroll_edge", "drawable", "android");
            Drawable androidEdge = context.getResources().getDrawable(edgeDrawableId);
            androidEdge.setColorFilter(brandColor, PorterDuff.Mode.SRC_IN);
        } catch (Resources.NotFoundException e) {
            // no hack on Android 5
        }
    }
}
