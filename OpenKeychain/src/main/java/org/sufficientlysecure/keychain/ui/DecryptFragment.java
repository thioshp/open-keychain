/*
 * Copyright (C) 2017 Schürmann & Breitmoser GbR
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


import java.util.ArrayList;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewAnimator;

import org.openintents.openpgp.OpenPgpDecryptionResult;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpUtils;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.keyimport.HkpKeyserverAddress;
import org.sufficientlysecure.keychain.keyimport.ParcelableKeyRing;
import org.sufficientlysecure.keychain.operations.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.operations.results.ImportKeyResult;
import org.sufficientlysecure.keychain.pgp.KeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.KeyRepository;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.service.ImportKeyringParcel;
import org.sufficientlysecure.keychain.ui.base.CryptoOperationHelper;
import org.sufficientlysecure.keychain.ui.keyview.ViewKeyActivity;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils.State;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.ui.util.Notify.Style;
import org.sufficientlysecure.keychain.util.Preferences;
import timber.log.Timber;


public abstract class DecryptFragment extends Fragment implements LoaderManager.LoaderCallbacks<Cursor> {

    public static final int LOADER_ID_UNIFIED = 0;
    public static final String ARG_DECRYPT_VERIFY_RESULT = "decrypt_verify_result";

    protected LinearLayout mResultLayout;
    protected ImageView mEncryptionIcon;
    protected TextView mEncryptionText;
    protected ImageView mSignatureIcon;
    protected TextView mSignatureText;
    protected View mSignatureLayout;
    protected TextView mSignatureName;
    protected TextView mSignatureEmail;
    protected TextView mSignatureAction;

    private OpenPgpSignatureResult mSignatureResult;
    private DecryptVerifyResult mDecryptVerifyResult;
    private ViewAnimator mOverlayAnimator;

    private CryptoOperationHelper<ImportKeyringParcel, ImportKeyResult> mImportOpHelper;

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // NOTE: These views are inside the activity!
        mResultLayout = getActivity().findViewById(R.id.result_main_layout);
        mResultLayout.setVisibility(View.GONE);
        mEncryptionIcon = getActivity().findViewById(R.id.result_encryption_icon);
        mEncryptionText = getActivity().findViewById(R.id.result_encryption_text);
        mSignatureIcon = getActivity().findViewById(R.id.result_signature_icon);
        mSignatureText = getActivity().findViewById(R.id.result_signature_text);
        mSignatureLayout = getActivity().findViewById(R.id.result_signature_layout);
        mSignatureName = getActivity().findViewById(R.id.result_signature_name);
        mSignatureEmail = getActivity().findViewById(R.id.result_signature_email);
        mSignatureAction = getActivity().findViewById(R.id.result_signature_action);

        // Overlay
        mOverlayAnimator = (ViewAnimator) view;
        Button vErrorOverlayButton = view.findViewById(R.id.decrypt_error_overlay_button);
        vErrorOverlayButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mOverlayAnimator.setDisplayedChild(0);
            }
        });
    }

    private void showErrorOverlay(boolean overlay) {
        int child = overlay ? 1 : 0;
        if (mOverlayAnimator.getDisplayedChild() != child) {
            mOverlayAnimator.setDisplayedChild(child);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(ARG_DECRYPT_VERIFY_RESULT, mDecryptVerifyResult);
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);

        if (savedInstanceState == null) {
            return;
        }

        DecryptVerifyResult result = savedInstanceState.getParcelable(ARG_DECRYPT_VERIFY_RESULT);
        if (result != null) {
            loadVerifyResult(result);
        }
    }

    private void lookupUnknownKey(long unknownKeyId) {

        final ArrayList<ParcelableKeyRing> keyList;
        final HkpKeyserverAddress keyserver;

        // search config
        keyserver = Preferences.getPreferences(getActivity()).getPreferredKeyserver();

        {
            ParcelableKeyRing keyEntry = ParcelableKeyRing.createFromReference(null,
                    KeyFormattingUtils.convertKeyIdToHex(unknownKeyId), null, null);
            ArrayList<ParcelableKeyRing> selectedEntries = new ArrayList<>();
            selectedEntries.add(keyEntry);

            keyList = selectedEntries;
        }

        CryptoOperationHelper.Callback<ImportKeyringParcel, ImportKeyResult> callback
                = new CryptoOperationHelper.Callback<ImportKeyringParcel, ImportKeyResult>() {

            @Override
            public ImportKeyringParcel createOperationInput() {
                return ImportKeyringParcel.createImportKeyringParcel(keyList, keyserver);
            }

            @Override
            public void onCryptoOperationSuccess(ImportKeyResult result) {
                result.createNotify(getActivity()).show();

                getLoaderManager().restartLoader(LOADER_ID_UNIFIED, null, DecryptFragment.this);
            }

            @Override
            public void onCryptoOperationCancelled() {
                // do nothing
            }

            @Override
            public void onCryptoOperationError(ImportKeyResult result) {
                result.createNotify(getActivity()).show();
            }

            @Override
            public boolean onCryptoSetProgress(String msg, int progress, int max) {
                return false;
            }
        };

        mImportOpHelper = new CryptoOperationHelper<>(1, this, callback, R.string.progress_importing);

        mImportOpHelper.cryptoOperation();
    }

    private void showKey(long keyId) {
        try {

            Intent viewKeyIntent = new Intent(getActivity(), ViewKeyActivity.class);
            KeyRepository keyRepository = KeyRepository.create(requireContext());
            long masterKeyId = keyRepository.getCachedPublicKeyRing(
                    KeyRings.buildUnifiedKeyRingsFindBySubkeyUri(keyId)
            ).getMasterKeyId();
            viewKeyIntent.setData(KeyRings.buildGenericKeyRingUri(masterKeyId));
            startActivity(viewKeyIntent);

        } catch (PgpKeyNotFoundException e) {
            Notify.create(getActivity(), R.string.error_key_not_found, Style.ERROR).show();
        }
    }

    protected void loadVerifyResult(DecryptVerifyResult decryptVerifyResult) {

        mDecryptVerifyResult = decryptVerifyResult;
        mSignatureResult = decryptVerifyResult.getSignatureResult();
        OpenPgpDecryptionResult decryptionResult = decryptVerifyResult.getDecryptionResult();

        mResultLayout.setVisibility(View.VISIBLE);

        switch (decryptionResult.getResult()) {
            case OpenPgpDecryptionResult.RESULT_ENCRYPTED: {
                mEncryptionText.setText(R.string.decrypt_result_encrypted);
                KeyFormattingUtils.setStatusImage(getActivity(), mEncryptionIcon, mEncryptionText, State.ENCRYPTED);
                break;
            }

            case OpenPgpDecryptionResult.RESULT_INSECURE: {
                mEncryptionText.setText(R.string.decrypt_result_insecure);
                KeyFormattingUtils.setStatusImage(getActivity(), mEncryptionIcon, mEncryptionText, State.INSECURE);
                break;
            }

            default:
            case OpenPgpDecryptionResult.RESULT_NOT_ENCRYPTED: {
                mEncryptionText.setText(R.string.decrypt_result_not_encrypted);
                KeyFormattingUtils.setStatusImage(getActivity(), mEncryptionIcon, mEncryptionText, State.NOT_ENCRYPTED);
                break;
            }
        }

        if (mSignatureResult.getResult() == OpenPgpSignatureResult.RESULT_NO_SIGNATURE) {
            // no signature

            setSignatureLayoutVisibility(View.GONE);

            mSignatureText.setText(R.string.decrypt_result_no_signature);
            KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.NOT_SIGNED);

            getLoaderManager().destroyLoader(LOADER_ID_UNIFIED);

            showErrorOverlay(false);

            onVerifyLoaded(true);
        } else {
            // signature present

            // after loader is restarted signature results are checked
            getLoaderManager().restartLoader(LOADER_ID_UNIFIED, null, this);
        }
    }

    private void setSignatureLayoutVisibility(int visibility) {
        mSignatureLayout.setVisibility(visibility);
    }

    private void setShowAction(final long signatureKeyId) {
        mSignatureAction.setText(R.string.decrypt_result_action_show);
        mSignatureAction.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_vpn_key_grey_24dp, 0);
        mSignatureLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKey(signatureKeyId);
            }
        });
    }

    // These are the rows that we will retrieve.
    static final String[] UNIFIED_PROJECTION = new String[]{
            KeychainContract.KeyRings._ID,
            KeychainContract.KeyRings.MASTER_KEY_ID,
            KeychainContract.KeyRings.USER_ID,
            KeychainContract.KeyRings.VERIFIED,
            KeychainContract.KeyRings.HAS_ANY_SECRET,
            KeyRings.NAME,
            KeyRings.EMAIL,
            KeyRings.COMMENT,
    };

    @SuppressWarnings("unused")
    static final int INDEX_MASTER_KEY_ID = 1;
    static final int INDEX_USER_ID = 2;
    static final int INDEX_VERIFIED = 3;
    static final int INDEX_HAS_ANY_SECRET = 4;
    static final int INDEX_NAME = 5;
    static final int INDEX_EMAIL = 6;
    static final int INDEX_COMMENT = 7;

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (id != LOADER_ID_UNIFIED) {
            return null;
        }

        Uri baseUri = KeychainContract.KeyRings.buildUnifiedKeyRingsFindBySubkeyUri(
                mSignatureResult.getKeyId());
        return new CursorLoader(getActivity(), baseUri, UNIFIED_PROJECTION, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {

        if (loader.getId() != LOADER_ID_UNIFIED) {
            return;
        }

        // If the key is unknown, show it as such
        if (data.getCount() == 0 || !data.moveToFirst()) {
            showUnknownKeyStatus();
            return;
        }

        long signatureKeyId = mSignatureResult.getKeyId();

        String name = data.getString(INDEX_NAME);
        String email = data.getString(INDEX_EMAIL);
        if (name != null) {
            mSignatureName.setText(name);
        } else {
            mSignatureName.setText(R.string.user_id_no_name);
        }
        if (email != null) {
            mSignatureEmail.setText(email);
        } else {
            mSignatureEmail.setText(KeyFormattingUtils.beautifyKeyIdWithPrefix(
                    mSignatureResult.getKeyId()));
        }

        // NOTE: Don't use revoked and expired fields from database, they don't show
        // revoked/expired subkeys
        boolean isRevoked = mSignatureResult.getResult() == OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED;
        boolean isExpired = mSignatureResult.getResult() == OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED;
        boolean isInsecure = mSignatureResult.getResult() == OpenPgpSignatureResult.RESULT_INVALID_KEY_INSECURE;
        boolean isVerified = data.getInt(INDEX_VERIFIED) > 0;
        boolean isYours = data.getInt(INDEX_HAS_ANY_SECRET) != 0;

        if (isRevoked) {
            mSignatureText.setText(R.string.decrypt_result_signature_revoked_key);
            KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.REVOKED);

            setSignatureLayoutVisibility(View.VISIBLE);
            setShowAction(signatureKeyId);

            onVerifyLoaded(true);

        } else if (isExpired) {
            mSignatureText.setText(R.string.decrypt_result_signature_expired_key);
            KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.EXPIRED);

            setSignatureLayoutVisibility(View.VISIBLE);
            setShowAction(signatureKeyId);

            showErrorOverlay(false);

            onVerifyLoaded(true);

        } else if (isInsecure) {
            mSignatureText.setText(R.string.decrypt_result_insecure_cryptography);
            KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.INSECURE);

            setSignatureLayoutVisibility(View.VISIBLE);
            setShowAction(signatureKeyId);

            showErrorOverlay(false);

            onVerifyLoaded(true);

        } else if (isYours) {

            mSignatureText.setText(R.string.decrypt_result_signature_secret);
            KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.VERIFIED);

            setSignatureLayoutVisibility(View.VISIBLE);
            setShowAction(signatureKeyId);

            showErrorOverlay(false);

            onVerifyLoaded(true);

        } else if (isVerified) {
            mSignatureText.setText(R.string.decrypt_result_signature_certified);
            KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.VERIFIED);

            setSignatureLayoutVisibility(View.VISIBLE);
            setShowAction(signatureKeyId);

            showErrorOverlay(false);

            onVerifyLoaded(true);

        } else {
            mSignatureText.setText(R.string.decrypt_result_signature_uncertified);
            KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.UNVERIFIED);

            setSignatureLayoutVisibility(View.VISIBLE);
            setShowAction(signatureKeyId);

            showErrorOverlay(false);

            onVerifyLoaded(true);
        }

    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

        if (loader.getId() != LOADER_ID_UNIFIED) {
            return;
        }

        setSignatureLayoutVisibility(View.GONE);
    }

    private void showUnknownKeyStatus() {

        final long signatureKeyId = mSignatureResult.getKeyId();

        int result = mSignatureResult.getResult();
        if (result != OpenPgpSignatureResult.RESULT_KEY_MISSING
                && result != OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE) {
            Timber.e("got missing status for non-missing key, shouldn't happen!");
        }

        String userId = mSignatureResult.getPrimaryUserId();
        OpenPgpUtils.UserId userIdSplit = KeyRing.splitUserId(userId);
        if (userIdSplit.name != null) {
            mSignatureName.setText(userIdSplit.name);
        } else {
            mSignatureName.setText(R.string.user_id_no_name);
        }
        if (userIdSplit.email != null) {
            mSignatureEmail.setText(userIdSplit.email);
        } else {
            mSignatureEmail.setText(KeyFormattingUtils.beautifyKeyIdWithPrefix(
                    mSignatureResult.getKeyId()));
        }

        switch (mSignatureResult.getResult()) {

            case OpenPgpSignatureResult.RESULT_KEY_MISSING: {
                mSignatureText.setText(R.string.decrypt_result_signature_missing_key);
                KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.UNKNOWN_KEY);

                setSignatureLayoutVisibility(View.VISIBLE);
                mSignatureAction.setText(R.string.decrypt_result_action_Lookup);
                mSignatureAction
                        .setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_file_download_grey_24dp, 0);
                mSignatureLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        lookupUnknownKey(signatureKeyId);
                    }
                });

                showErrorOverlay(false);

                onVerifyLoaded(true);

                break;
            }

            case OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE: {
                mSignatureText.setText(R.string.decrypt_result_invalid_signature);
                KeyFormattingUtils.setStatusImage(getActivity(), mSignatureIcon, mSignatureText, State.INVALID);

                setSignatureLayoutVisibility(View.GONE);

                showErrorOverlay(true);

                onVerifyLoaded(false);
                break;
            }

        }

    }

    protected abstract void onVerifyLoaded(boolean hideErrorOverlay);

    public void startDisplayLogActivity() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, LogDisplayActivity.class);
        intent.putExtra(LogDisplayFragment.EXTRA_RESULT, mDecryptVerifyResult);
        activity.startActivity(intent);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mImportOpHelper != null) {
            mImportOpHelper.handleActivityResult(requestCode, resultCode, data);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
