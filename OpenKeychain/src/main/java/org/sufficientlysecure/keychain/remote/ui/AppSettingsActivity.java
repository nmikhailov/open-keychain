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

package org.sufficientlysecure.keychain.remote.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.getbase.floatingactionbutton.FloatingActionButton;

import org.spongycastle.util.encoders.Hex;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.remote.AppSettings;
import org.sufficientlysecure.keychain.ui.base.BaseActivity;
import org.sufficientlysecure.keychain.ui.dialog.AdvancedAppSettingsDialogFragment;
import org.sufficientlysecure.keychain.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class AppSettingsActivity extends BaseActivity {
    private Uri mAppUri;

    private AppSettingsAllowedKeysListFragment mAllowedKeysFragment;

    private TextView mAppNameView;
    private ImageView mAppIconView;
    private TextView mPackageName;
    private TextView mPackageSignature;

    private FloatingActionButton mStartFab;

    // deprecated API
    private AccountsListFragment mAccountsListFragment;
    private TextView mAccountsLabel;


    // model
    AppSettings mAppSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAccountsLabel = (TextView) findViewById(R.id.api_accounts_label);
        mAppNameView = (TextView) findViewById(R.id.api_app_settings_app_name);
        mAppIconView = (ImageView) findViewById(R.id.api_app_settings_app_icon);
        mPackageName = (TextView) findViewById(R.id.api_app_settings_package_name);
        mPackageSignature = (TextView) findViewById(R.id.api_app_settings_package_signature);
        mStartFab = (FloatingActionButton) findViewById(R.id.fab);

        mStartFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startApp();
            }
        });

        setFullScreenDialogClose(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancel();
            }
        });
        setTitle(null);

        Intent intent = getIntent();
        mAppUri = intent.getData();
        if (mAppUri == null) {
            Log.e(Constants.TAG, "Intent data missing. Should be Uri of app!");
            finish();
            return;
        } else {
            Log.d(Constants.TAG, "uri: " + mAppUri);
            loadData(savedInstanceState, mAppUri);
        }
    }

    private void save() {
        mAllowedKeysFragment.saveAllowedKeys();
        setResult(Activity.RESULT_OK);
        finish();
    }

    private void cancel() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    @Override
    protected void initLayout() {
        setContentView(R.layout.api_app_settings_activity);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.api_app_settings, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_api_save: {
                save();
                return true;
            }
            case R.id.menu_api_settings_revoke: {
                revokeAccess();
                return true;
            }
            case R.id.menu_api_settings_advanced: {
                showAdvancedInfo();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAdvancedInfo() {
        String signature = null;
        // advanced info: package signature SHA-256
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(mAppSettings.getPackageSignature());
            byte[] digest = md.digest();
            signature = new String(Hex.encode(digest));
        } catch (NoSuchAlgorithmException e) {
            Log.e(Constants.TAG, "Should not happen!", e);
        }

        AdvancedAppSettingsDialogFragment dialogFragment =
                AdvancedAppSettingsDialogFragment.newInstance(mAppSettings.getPackageName(), signature);

        dialogFragment.show(getSupportFragmentManager(), "advancedDialog");
    }

    private void startApp() {
        Intent i;
        PackageManager manager = getPackageManager();
        try {
            i = manager.getLaunchIntentForPackage(mAppSettings.getPackageName());
            if (i == null)
                throw new PackageManager.NameNotFoundException();
            // start like the Android launcher would do
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            startActivity(i);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(Constants.TAG, "startApp", e);
        }
    }

    private void loadData(Bundle savedInstanceState, Uri appUri) {
        mAppSettings = new ProviderHelper(this).getApiAppSettings(appUri);

        // get application name and icon from package manager
        String appName;
        Drawable appIcon = null;
        PackageManager pm = getApplicationContext().getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(mAppSettings.getPackageName(), 0);

            appName = (String) pm.getApplicationLabel(ai);
            appIcon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException e) {
            // fallback
            appName = mAppSettings.getPackageName();
        }
        mAppNameView.setText(appName);
        mAppIconView.setImageDrawable(appIcon);

        Uri accountsUri = appUri.buildUpon().appendPath(KeychainContract.PATH_ACCOUNTS).build();
        Log.d(Constants.TAG, "accountsUri: " + accountsUri);
        Uri allowedKeysUri = appUri.buildUpon().appendPath(KeychainContract.PATH_ALLOWED_KEYS).build();
        Log.d(Constants.TAG, "allowedKeysUri: " + allowedKeysUri);
        startListFragments(savedInstanceState, accountsUri, allowedKeysUri);
    }

    private void startListFragments(Bundle savedInstanceState, Uri accountsUri, Uri allowedKeysUri) {
        // However, if we're being restored from a previous state,
        // then we don't need to do anything and should return or else
        // we could end up with overlapping fragments.
        if (savedInstanceState != null) {
            return;
        }

        // show accounts only if available (deprecated API)
        Cursor cursor = getContentResolver().query(accountsUri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) try {
            mAccountsLabel.setVisibility(View.VISIBLE);
            mAccountsListFragment = AccountsListFragment.newInstance(accountsUri);
            // Create an instance of the fragments
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.api_accounts_list_fragment, mAccountsListFragment)
                    .commitAllowingStateLoss();
        } finally {
            cursor.close();
        }

        // Create an instance of the fragments
        mAllowedKeysFragment = AppSettingsAllowedKeysListFragment.newInstance(allowedKeysUri);
        // Add the fragment to the 'fragment_container' FrameLayout
        // NOTE: We use commitAllowingStateLoss() to prevent weird crashes!
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.api_allowed_keys_list_fragment, mAllowedKeysFragment)
                .commitAllowingStateLoss();
        // do it immediately!
        getSupportFragmentManager().executePendingTransactions();
    }

    private void revokeAccess() {
        if (getContentResolver().delete(mAppUri, null, null) <= 0) {
            throw new RuntimeException();
        }
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // if a result has been returned, display a notify
        if (data != null && data.hasExtra(OperationResult.EXTRA_RESULT)) {
            OperationResult result = data.getParcelableExtra(OperationResult.EXTRA_RESULT);
            result.createNotify(this).show();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

}
