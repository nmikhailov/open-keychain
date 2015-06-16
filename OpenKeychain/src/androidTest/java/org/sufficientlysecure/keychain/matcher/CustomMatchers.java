package org.sufficientlysecure.keychain.matcher;


import android.support.annotation.ColorRes;
import android.support.test.espresso.matcher.BoundedMatcher;
import android.view.View;

import com.nispok.snackbar.Snackbar;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.sufficientlysecure.keychain.EncryptKeyCompletionViewTest;
import org.sufficientlysecure.keychain.ui.adapter.KeyAdapter.KeyItem;
import org.sufficientlysecure.keychain.ui.widget.EncryptKeyCompletionView;

import static android.support.test.internal.util.Checks.checkNotNull;


public abstract class CustomMatchers {

    public static Matcher<View> withSnackbarLineColor(@ColorRes final int colorRes) {
        return new BoundedMatcher<View, Snackbar>(Snackbar.class) {
            public void describeTo(Description description) {
                description.appendText("with color resource id: " + colorRes);
            }

            @Override
            public boolean matchesSafely(Snackbar snackbar) {
                return snackbar.getResources().getColor(colorRes) == snackbar.getLineColor();
            }
        };
    }

    public static Matcher<Object> withKeyItemId(final long keyId) {
        return new BoundedMatcher<Object, KeyItem>(KeyItem.class) {
            @Override
            public boolean matchesSafely(KeyItem item) {
                return item.mKeyId == keyId;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("with key id: " + keyId);
            }
        };
    }

    public static Matcher<View> withKeyToken(@ColorRes final long keyId) {
        return new BoundedMatcher<View, EncryptKeyCompletionView>(EncryptKeyCompletionView.class) {
            public void describeTo(Description description) {
                description.appendText("with key id token: " + keyId);
            }

            @Override
            public boolean matchesSafely(EncryptKeyCompletionView tokenView) {
                for (Object object : tokenView.getObjects()) {
                    if (object instanceof KeyItem && ((KeyItem) object).mKeyId == keyId) {
                        return true;
                    }
                }
                return false;
            }
        };
    }


}
