package org.sufficientlysecure.keychain.ui.linked;

import java.io.IOException;
import java.util.Collections;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentManager.OnBackStackChangedListener;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.ViewAnimator;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.Constants.key;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.operations.results.LinkedVerifyResult;
import org.sufficientlysecure.keychain.linked.LinkedTokenResource;
import org.sufficientlysecure.keychain.linked.LinkedAttribute;
import org.sufficientlysecure.keychain.linked.LinkedResource;
import org.sufficientlysecure.keychain.linked.UriAttribute;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.provider.KeychainContract.Certs;
import org.sufficientlysecure.keychain.provider.KeychainContract.UserPackets;
import org.sufficientlysecure.keychain.provider.KeychainDatabase.Tables;
import org.sufficientlysecure.keychain.service.CertifyActionsParcel;
import org.sufficientlysecure.keychain.service.CertifyActionsParcel.CertifyAction;
import org.sufficientlysecure.keychain.ui.adapter.LinkedIdsAdapter;
import org.sufficientlysecure.keychain.ui.adapter.UserIdsAdapter;
import org.sufficientlysecure.keychain.ui.base.CryptoOperationFragment;
import org.sufficientlysecure.keychain.ui.linked.LinkedIdViewFragment.ViewHolder.VerifyState;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils.State;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.util.Notify.Style;
import org.sufficientlysecure.keychain.ui.util.SubtleAttentionSeeker;
import org.sufficientlysecure.keychain.ui.widget.CertListWidget;
import org.sufficientlysecure.keychain.ui.widget.CertifyKeySpinner;
import org.sufficientlysecure.keychain.util.Log;

public class LinkedIdViewFragment extends CryptoOperationFragment implements
        LoaderManager.LoaderCallbacks<Cursor>, OnBackStackChangedListener {

    private static final String ARG_DATA_URI = "data_uri";
    private static final String ARG_LID_RANK = "rank";
    private static final String ARG_IS_SECRET = "verified";
    private static final String ARG_FINGERPRINT = "fingerprint";
    private static final int LOADER_ID_LINKED_ID = 1;

    private UriAttribute mLinkedId;
    private LinkedTokenResource mLinkedResource;
    private boolean mIsSecret;

    private Context mContext;
    private byte[] mFingerprint;

    private AsyncTask mInProgress;

    private Uri mDataUri;
    private ViewHolder mViewHolder;
    private int mLidRank;
    private OnIdentityLoadedListener mIdLoadedListener;
    private long mCertifyKeyId;

    public static LinkedIdViewFragment newInstance(Uri dataUri, int rank,
            boolean isSecret, byte[] fingerprint) throws IOException {
        LinkedIdViewFragment frag = new LinkedIdViewFragment();

        Bundle args = new Bundle();
        args.putParcelable(ARG_DATA_URI, dataUri);
        args.putInt(ARG_LID_RANK, rank);
        args.putBoolean(ARG_IS_SECRET, isSecret);
        args.putByteArray(ARG_FINGERPRINT, fingerprint);
        frag.setArguments(args);

        return frag;
    }

    public LinkedIdViewFragment() {
        // IMPORTANT: the id must be unique in the ViewKeyActivity CryptoOperationHelper id namespace!
        // no initial progress message -> we handle progress ourselves!
        super(5, null);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        mDataUri = args.getParcelable(ARG_DATA_URI);
        mLidRank = args.getInt(ARG_LID_RANK);

        mIsSecret = args.getBoolean(ARG_IS_SECRET);
        mFingerprint = args.getByteArray(ARG_FINGERPRINT);

        mContext = getActivity();

        getLoaderManager().initLoader(LOADER_ID_LINKED_ID, null, this);

    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        switch (id) {
            case LOADER_ID_LINKED_ID:
                return new CursorLoader(getActivity(), mDataUri,
                        UserIdsAdapter.USER_PACKETS_PROJECTION,
                        Tables.USER_PACKETS + "." + UserPackets.RANK
                                + " = " + Integer.toString(mLidRank), null, null);
            default:
                return null;
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        switch (loader.getId()) {
            case LOADER_ID_LINKED_ID:

                // Nothing to load means break if we are *expected* to load
                if (!cursor.moveToFirst()) {
                    if (mIdLoadedListener != null) {
                        Notify.create(getActivity(), "Error loading identity!",
                                Notify.LENGTH_LONG, Style.ERROR).show();
                        finishFragment();
                    }
                    // Or just ignore, this is probably some intermediate state during certify
                    break;
                }

                try {
                    int certStatus = cursor.getInt(UserIdsAdapter.INDEX_VERIFIED);

                    byte[] data = cursor.getBlob(UserIdsAdapter.INDEX_ATTRIBUTE_DATA);
                    UriAttribute linkedId = LinkedAttribute.fromAttributeData(data);

                    loadIdentity(linkedId, certStatus);

                    if (mIdLoadedListener != null) {
                        mIdLoadedListener.onIdentityLoaded();
                        mIdLoadedListener = null;
                    }

                } catch (IOException e) {
                    Log.e(Constants.TAG, "error parsing identity", e);
                    Notify.create(getActivity(), "Error parsing identity!",
                            Notify.LENGTH_LONG, Style.ERROR).show();
                    finishFragment();
                }

                break;
        }
    }

    public void finishFragment() {
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                FragmentManager manager = getFragmentManager();
                manager.removeOnBackStackChangedListener(LinkedIdViewFragment.this);
                manager.popBackStack("linked_id", FragmentManager.POP_BACK_STACK_INCLUSIVE);
            }
        });
    }

    public interface OnIdentityLoadedListener {
        void onIdentityLoaded();
    }

    public void setOnIdentityLoadedListener(OnIdentityLoadedListener listener) {
        mIdLoadedListener = listener;
    }

    private void loadIdentity(UriAttribute linkedId, int certStatus) {
        mLinkedId = linkedId;

        if (mLinkedId instanceof LinkedAttribute) {
            LinkedResource res = ((LinkedAttribute) mLinkedId).mResource;
            mLinkedResource = (LinkedTokenResource) res;
        }

        if (!mIsSecret) {
            switch (certStatus) {
                case Certs.VERIFIED_SECRET:
                    KeyFormattingUtils.setStatusImage(mContext, mViewHolder.mLinkedIdHolder.vVerified,
                            null, State.VERIFIED, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
                case Certs.VERIFIED_SELF:
                    KeyFormattingUtils.setStatusImage(mContext, mViewHolder.mLinkedIdHolder.vVerified,
                            null, State.UNVERIFIED, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
                default:
                    KeyFormattingUtils.setStatusImage(mContext, mViewHolder.mLinkedIdHolder.vVerified,
                            null, State.INVALID, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
            }
        }

        mViewHolder.mLinkedIdHolder.setData(mContext, mLinkedId);

        setShowVerifying(false);

        // no resource, nothing further we can do…
        if (mLinkedResource == null) {
            mViewHolder.vButtonView.setVisibility(View.GONE);
            mViewHolder.vButtonVerify.setVisibility(View.GONE);
            return;
        }

        if (mLinkedResource.isViewable()) {
            mViewHolder.vButtonView.setVisibility(View.VISIBLE);
            mViewHolder.vButtonView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = mLinkedResource.getViewIntent();
                    if (intent == null) {
                        return;
                    }
                    getActivity().startActivity(intent);
                }
            });
        } else {
            mViewHolder.vButtonView.setVisibility(View.GONE);
        }

    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }

    static class ViewHolder {
        private final View vButtonView;
        private final ViewAnimator vVerifyingContainer;
        private final ViewAnimator vItemCertified;
        private final View vKeySpinnerContainer;
        LinkedIdsAdapter.ViewHolder mLinkedIdHolder;

        private ViewAnimator vButtonSwitcher;
        private CertListWidget vLinkedCerts;
        private CertifyKeySpinner vKeySpinner;
        private final View vButtonVerify;
        private final View vButtonRetry;
        private final View vButtonConfirm;

        private final ViewAnimator vProgress;
        private final TextSwitcher vText;

        ViewHolder(View root) {
            vLinkedCerts = (CertListWidget) root.findViewById(R.id.linked_id_certs);
            vKeySpinner = (CertifyKeySpinner) root.findViewById(R.id.cert_key_spinner);
            vKeySpinnerContainer = root.findViewById(R.id.cert_key_spincontainer);
            vButtonSwitcher = (ViewAnimator) root.findViewById(R.id.button_animator);

            mLinkedIdHolder = new LinkedIdsAdapter.ViewHolder(root);

            vButtonVerify = root.findViewById(R.id.button_verify);
            vButtonRetry = root.findViewById(R.id.button_retry);
            vButtonConfirm = root.findViewById(R.id.button_confirm);
            vButtonView = root.findViewById(R.id.button_view);

            vVerifyingContainer = (ViewAnimator) root.findViewById(R.id.linked_verify_container);
            vItemCertified = (ViewAnimator) root.findViewById(R.id.linked_id_certified);

            vProgress = (ViewAnimator) root.findViewById(R.id.linked_cert_progress);
            vText = (TextSwitcher) root.findViewById(R.id.linked_cert_text);
        }

        enum VerifyState {
            VERIFYING, VERIFY_OK, VERIFY_ERROR, CERTIFYING
        }

        void setVerifyingState(Context context, VerifyState state, boolean isSecret) {
            switch (state) {
                case VERIFYING:
                    vProgress.setDisplayedChild(0);
                    vText.setText(context.getString(R.string.linked_text_verifying));
                    vKeySpinnerContainer.setVisibility(View.GONE);
                    break;

                case VERIFY_OK:
                    vProgress.setDisplayedChild(1);
                    if (!isSecret) {
                        showButton(2);
                        if (!vKeySpinner.isSingleEntry()) {
                            vKeySpinnerContainer.setVisibility(View.VISIBLE);
                        }
                    } else {
                        showButton(1);
                        vKeySpinnerContainer.setVisibility(View.GONE);
                    }
                    break;

                case VERIFY_ERROR:
                    showButton(1);
                    vProgress.setDisplayedChild(2);
                    vText.setText(context.getString(R.string.linked_text_error));
                    vKeySpinnerContainer.setVisibility(View.GONE);
                    break;

                case CERTIFYING:
                    vProgress.setDisplayedChild(0);
                    vText.setText(context.getString(R.string.linked_text_confirming));
                    vKeySpinnerContainer.setVisibility(View.GONE);
                    break;
            }
        }

        void showVerifyingContainer(Context context, boolean show, boolean isSecret) {
            if (vVerifyingContainer.getDisplayedChild() == (show ? 1 : 0)) {
                return;
            }

            vVerifyingContainer.setInAnimation(context, show ? R.anim.fade_in_up : R.anim.fade_in_down);
            vVerifyingContainer.setOutAnimation(context, show ? R.anim.fade_out_up : R.anim.fade_out_down);
            vVerifyingContainer.setDisplayedChild(show ? 1 : 0);

            vItemCertified.setInAnimation(context, show ? R.anim.fade_in_up : R.anim.fade_in_down);
            vItemCertified.setOutAnimation(context, show ? R.anim.fade_out_up : R.anim.fade_out_down);
            vItemCertified.setDisplayedChild(show || isSecret ? 1 : 0);
        }

        void showButton(int which) {
            if (vButtonSwitcher.getDisplayedChild() == which) {
                return;
            }
            vButtonSwitcher.setDisplayedChild(which);
        }

    }

    private boolean mVerificationState = false;
    /** Switches between the 'verifying' ui bit and certificate status. This method
     * must behave correctly in all states, showing or hiding the appropriate views
     * and cancelling pending operations where necessary.
     *
     * This method also handles back button functionality in combination with
     * onBackStateChanged.
     */
    void setShowVerifying(boolean show) {
        if (!show) {
            if (mInProgress != null) {
                mInProgress.cancel(false);
                mInProgress = null;
            }
            getFragmentManager().removeOnBackStackChangedListener(this);
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    getFragmentManager().popBackStack("verification",
                            FragmentManager.POP_BACK_STACK_INCLUSIVE);
                }
            });

            if (!mVerificationState) {
                return;
            }
            mVerificationState = false;

            mViewHolder.showButton(0);
            mViewHolder.vKeySpinnerContainer.setVisibility(View.GONE);
            mViewHolder.showVerifyingContainer(mContext, false, mIsSecret);
            return;
        }

        if (mVerificationState) {
            return;
        }
        mVerificationState = true;

        FragmentManager manager = getFragmentManager();
        manager.beginTransaction().addToBackStack("verification").commit();
        manager.executePendingTransactions();
        manager.addOnBackStackChangedListener(this);
        mViewHolder.showVerifyingContainer(mContext, true, mIsSecret);

    }

    @Override
    public void onBackStackChanged() {
        setShowVerifying(false);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup superContainer, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.linked_id_view_fragment, null);

        mViewHolder = new ViewHolder(root);
        root.setTag(mViewHolder);

        ((ImageView) root.findViewById(R.id.status_icon_verified))
                .setColorFilter(mContext.getResources().getColor(R.color.android_green_light),
                        PorterDuff.Mode.SRC_IN);
        ((ImageView) root.findViewById(R.id.status_icon_invalid))
                .setColorFilter(mContext.getResources().getColor(R.color.android_red_light),
                        PorterDuff.Mode.SRC_IN);

        mViewHolder.vButtonVerify.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyResource();
            }
        });
        mViewHolder.vButtonRetry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyResource();
            }
        });
        mViewHolder.vButtonConfirm.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                initiateCertifying();
            }
        });

        {
            Bundle args = new Bundle();
            args.putParcelable(CertListWidget.ARG_URI, Certs.buildLinkedIdCertsUri(mDataUri, mLidRank));
            args.putBoolean(CertListWidget.ARG_IS_SECRET, mIsSecret);
            getLoaderManager().initLoader(CertListWidget.LOADER_ID_LINKED_CERTS,
                    args, mViewHolder.vLinkedCerts);
        }

        return root;
    }

    void verifyResource() {

        // only one at a time (no sync needed, mInProgress is only touched in ui thread)
        if (mInProgress != null) {
            return;
        }

        setShowVerifying(true);

        mViewHolder.vKeySpinnerContainer.setVisibility(View.GONE);
        mViewHolder.setVerifyingState(mContext, VerifyState.VERIFYING, mIsSecret);

        mInProgress = new AsyncTask<Void,Void,LinkedVerifyResult>() {
            @Override
            protected LinkedVerifyResult doInBackground(Void... params) {
                long timer = System.currentTimeMillis();
                LinkedVerifyResult result = mLinkedResource.verify(getActivity(), mFingerprint);

                // ux flow: this operation should take at last a second
                timer = System.currentTimeMillis() -timer;
                if (timer < 1000) try {
                    Thread.sleep(1000 -timer);
                } catch (InterruptedException e) {
                    // never mind
                }

                return result;
            }

            @Override
            protected void onPostExecute(LinkedVerifyResult result) {
                if (isCancelled()) {
                    return;
                }
                if (result.success()) {
                    mViewHolder.vText.setText(getString(mLinkedResource.getVerifiedText(mIsSecret)));
                    // hack to preserve bold text
                    ((TextView) mViewHolder.vText.getCurrentView()).setText(
                            mLinkedResource.getVerifiedText(mIsSecret));
                    mViewHolder.setVerifyingState(mContext, VerifyState.VERIFY_OK, mIsSecret);
                    mViewHolder.mLinkedIdHolder.seekAttention();
                } else {
                    mViewHolder.setVerifyingState(mContext, VerifyState.VERIFY_ERROR, mIsSecret);
                    result.createNotify(getActivity()).show();
                }
                mInProgress = null;
            }
        }.execute();

    }

    private void initiateCertifying() {

        if (mIsSecret) {
            return;
        }

        // get the user's passphrase for this key (if required)
        mCertifyKeyId = mViewHolder.vKeySpinner.getSelectedKeyId();
        if (mCertifyKeyId == key.none || mCertifyKeyId == key.symmetric) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                SubtleAttentionSeeker.tintBackground(mViewHolder.vKeySpinnerContainer, 600).start();
            } else {
                Notify.create(getActivity(), R.string.select_key_to_certify, Style.ERROR).show();
            }
            return;
        }

        mViewHolder.setVerifyingState(mContext, VerifyState.CERTIFYING, false);
        cryptoOperation();

    }

    @Override
    public void onCryptoOperationCancelled() {
        super.onCryptoOperationCancelled();

        // go back to 'verified ok'
        setShowVerifying(false);

    }

    @Nullable
    @Override
    public Parcelable createOperationInput() {
        long masterKeyId = KeyFormattingUtils.convertFingerprintToKeyId(mFingerprint);
        CertifyAction action = new CertifyAction(masterKeyId, null,
                Collections.singletonList(mLinkedId.toUserAttribute()));

        // fill values for this action
        CertifyActionsParcel parcel = new CertifyActionsParcel(mCertifyKeyId);
        parcel.mCertifyActions.addAll(Collections.singletonList(action));

        return parcel;
    }

    @Override
    public void onCryptoOperationSuccess(OperationResult result) {
        result.createNotify(getActivity()).show();
        // no need to do anything else, we will get a loader refresh!
    }

    @Override
    public void onCryptoOperationError(OperationResult result) {
        result.createNotify(getActivity()).show();
    }

    @Override
    public boolean onCryptoSetProgress(String msg, int progress, int max) {
        return true;
    }

}
