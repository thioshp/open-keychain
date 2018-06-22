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


import java.util.List;

import android.arch.lifecycle.LiveData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ViewAnimator;

import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.compatibility.DialogFragmentWorkaround;
import org.sufficientlysecure.keychain.livedata.GenericLiveData;
import org.sufficientlysecure.keychain.model.UserPacket.UserId;
import org.sufficientlysecure.keychain.operations.results.EditKeyResult;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.CachedPublicKeyRing;
import org.sufficientlysecure.keychain.provider.KeyRepository;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.Certs;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel;
import org.sufficientlysecure.keychain.ui.adapter.UserIdsAdapter;
import org.sufficientlysecure.keychain.ui.adapter.UserIdsAddedAdapter;
import org.sufficientlysecure.keychain.ui.base.CryptoOperationHelper;
import org.sufficientlysecure.keychain.ui.base.LoaderFragment;
import org.sufficientlysecure.keychain.ui.dialog.AddUserIdDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.EditUserIdDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.SetPassphraseDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.UserIdInfoDialogFragment;
import timber.log.Timber;


public class ViewKeyAdvUserIdsFragment extends LoaderFragment implements
        LoaderManager.LoaderCallbacks<Cursor> {

    public static final String ARG_DATA_URI = "uri";

    private static final int LOADER_ID_UNIFIED = 0;

    private ListView mUserIds;
    private ListView mUserIdsAddedList;
    private View mUserIdsAddedLayout;
    private ViewAnimator mUserIdAddFabLayout;

    private UserIdsAdapter mUserIdsAdapter;
    private UserIdsAddedAdapter mUserIdsAddedAdapter;

    private CryptoOperationHelper<SaveKeyringParcel, EditKeyResult> mEditKeyHelper;

    private Uri mDataUri;

    private long mMasterKeyId;
    private byte[] mFingerprint;
    private boolean mHasSecret;
    private SaveKeyringParcel.Builder mSkpBuilder;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup superContainer, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, superContainer, savedInstanceState);
        View view = inflater.inflate(R.layout.view_key_adv_user_ids_fragment, getContainer());

        mUserIds = view.findViewById(R.id.view_key_user_ids);
        mUserIdsAddedList = view.findViewById(R.id.view_key_user_ids_added);
        mUserIdsAddedLayout = view.findViewById(R.id.view_key_user_ids_add_layout);

        mUserIds.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showOrEditUserIdInfo(position);
            }
        });

        View footer = new View(getActivity());
        int spacing = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 72, getResources().getDisplayMetrics()
        );
        android.widget.AbsListView.LayoutParams params = new android.widget.AbsListView.LayoutParams(
                android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                spacing
        );
        footer.setLayoutParams(params);
        mUserIdsAddedList.addFooterView(footer, null, false);

        mUserIdAddFabLayout = view.findViewById(R.id.view_key_subkey_fab_layout);
        view.findViewById(R.id.view_key_subkey_fab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addUserId();
            }
        });

        setHasOptionsMenu(true);

        return root;
    }

    private void showOrEditUserIdInfo(final int position) {
        if (mSkpBuilder != null) {
            editUserId(position);
        } else {
            showUserIdInfo(position);
        }
    }

    private void editUserId(final int position) {
        final String userId = mUserIdsAdapter.getUserId(position);
        final boolean isRevoked = mUserIdsAdapter.getIsRevoked(position);
        final boolean isRevokedPending = mUserIdsAdapter.getIsRevokedPending(position);

        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case EditUserIdDialogFragment.MESSAGE_CHANGE_PRIMARY_USER_ID:
                        // toggle
                        if (mSkpBuilder.getChangePrimaryUserId() != null
                                && mSkpBuilder.getChangePrimaryUserId().equals(userId)) {
                            mSkpBuilder.setChangePrimaryUserId(null);
                        } else {
                            mSkpBuilder.setChangePrimaryUserId(userId);
                        }
                        break;
                    case EditUserIdDialogFragment.MESSAGE_REVOKE:
                        // toggle
                        if (mSkpBuilder.getMutableRevokeUserIds().contains(userId)) {
                            mSkpBuilder.removeRevokeUserId(userId);
                        } else {
                            mSkpBuilder.addRevokeUserId(userId);
                            // not possible to revoke and change to primary user id
                            if (mSkpBuilder.getChangePrimaryUserId() != null
                                    && mSkpBuilder.getChangePrimaryUserId().equals(userId)) {
                                mSkpBuilder.setChangePrimaryUserId(null);
                            }
                        }
                        break;
                }
                mUserIdsAdapter.notifyDataSetChanged();
            }
        };

        // Create a new Messenger for the communication back
        final Messenger messenger = new Messenger(returnHandler);

        DialogFragmentWorkaround.INTERFACE.runnableRunDelayed(new Runnable() {
            public void run() {
                EditUserIdDialogFragment dialogFragment =
                        EditUserIdDialogFragment.newInstance(messenger, isRevoked, isRevokedPending);
                dialogFragment.show(getActivity().getSupportFragmentManager(), "editUserIdDialog");
            }
        });
    }

    private void showUserIdInfo(final int position) {

        final boolean isRevoked = mUserIdsAdapter.getIsRevoked(position);
        final boolean isVerified = mUserIdsAdapter.getIsVerified(position) == Certs.VERIFIED_SECRET;

        DialogFragmentWorkaround.INTERFACE.runnableRunDelayed(new Runnable() {
            public void run() {
                UserIdInfoDialogFragment dialogFragment =
                        UserIdInfoDialogFragment.newInstance(isRevoked, isVerified);

                dialogFragment.show(getActivity().getSupportFragmentManager(), "userIdInfoDialog");
            }
        });
    }

    private void addUserId() {
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == SetPassphraseDialogFragment.MESSAGE_OKAY) {
                    Bundle data = message.getData();

                    // add new user id
                    mUserIdsAddedAdapter.add(data
                            .getString(AddUserIdDialogFragment.MESSAGE_DATA_USER_ID));
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(returnHandler);

        // pre-fill out primary name
        AddUserIdDialogFragment addUserIdDialog = AddUserIdDialogFragment.newInstance(messenger, "");

        addUserIdDialog.show(getActivity().getSupportFragmentManager(), "addUserIdDialog");
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        Uri dataUri = getArguments().getParcelable(ARG_DATA_URI);
        if (dataUri == null) {
            Timber.e("Data missing. Should be Uri of key!");
            getActivity().finish();
            return;
        }

        loadData(dataUri);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mEditKeyHelper != null) {
            mEditKeyHelper.handleActivityResult(requestCode, resultCode, data);
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void loadData(Uri dataUri) {
        mDataUri = dataUri;

        Timber.i("dataUri: " + mDataUri);

        mUserIdsAdapter = new UserIdsAdapter(getActivity(), false);
        mUserIds.setAdapter(mUserIdsAdapter);

        // Prepare the loaders. Either re-connect with an existing ones,
        // or start new ones.
        getLoaderManager().initLoader(LOADER_ID_UNIFIED, null, this);

        KeyRepository keyRepository = KeyRepository.create(getContext());
        try {
            Uri uri = KeychainContract.KeyRings.buildUnifiedKeyRingUri(mDataUri);
            CachedPublicKeyRing keyRing = keyRepository.getCachedPublicKeyRing(uri);
            long masterKeyId = keyRing.getMasterKeyId();

            LiveData<List<UserId>> userIdLiveData =
                    new GenericLiveData<>(getContext(), null, () -> keyRepository.getUserIds(masterKeyId));
            userIdLiveData.observe(this, this::onUserIdsLoaded);
        } catch (PgpKeyNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void onUserIdsLoaded(List<UserId> userIds) {
        mUserIdsAdapter.setData(userIds);
        setContentShown(true);
    }

    // These are the rows that we will retrieve.
    static final String[] PROJECTION = new String[]{
            KeychainContract.KeyRings._ID,
            KeychainContract.KeyRings.MASTER_KEY_ID,
            KeychainContract.KeyRings.HAS_ANY_SECRET,
            KeychainContract.KeyRings.FINGERPRINT,
    };

    static final int INDEX_MASTER_KEY_ID = 1;
    static final int INDEX_HAS_ANY_SECRET = 2;
    static final int INDEX_FINGERPRINT = 3;

    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        switch (id) {
            case LOADER_ID_UNIFIED: {
                Uri baseUri = KeychainContract.KeyRings.buildUnifiedKeyRingUri(mDataUri);
                return new CursorLoader(getActivity(), baseUri,
                        PROJECTION, null, null, null);
            }

            default:
                return null;
        }
    }

    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // Avoid NullPointerExceptions, if we get an empty result set.
        if (data.getCount() == 0) {
            return;
        }

        switch (loader.getId()) {
            case LOADER_ID_UNIFIED: {
                data.moveToFirst();

                mMasterKeyId = data.getLong(INDEX_MASTER_KEY_ID);
                mHasSecret = data.getInt(INDEX_HAS_ANY_SECRET) != 0;
                mFingerprint = data.getBlob(INDEX_FINGERPRINT);
                break;
            }
        }
    }

    /**
     * This is called when the last Cursor provided to onLoadFinished() above is about to be closed.
     * We need to make sure we are no longer using it.
     */
    public void onLoaderReset(Loader<Cursor> loader) {

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_action_mode_edit:
                enterEditMode();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void enterEditMode() {
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }
        activity.startActionMode(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {

                mSkpBuilder = SaveKeyringParcel.buildChangeKeyringParcel(mMasterKeyId, mFingerprint);

                mUserIdsAddedAdapter =
                        new UserIdsAddedAdapter(getActivity(), mSkpBuilder.getMutableAddUserIds(), false);
                mUserIdsAddedList.setAdapter(mUserIdsAddedAdapter);
                mUserIdsAddedLayout.setVisibility(View.VISIBLE);
                mUserIdAddFabLayout.setDisplayedChild(1);

                mUserIdsAdapter.setEditMode(mSkpBuilder);
                mUserIdsAdapter.notifyDataSetChanged();

                mode.setTitle(R.string.title_edit_identities);
                mode.getMenuInflater().inflate(R.menu.action_edit_uids, menu);

                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                editKey(mode);
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                mSkpBuilder = null;
                mUserIdsAdapter.setEditMode(null);
                mUserIdsAddedLayout.setVisibility(View.GONE);
                mUserIdAddFabLayout.setDisplayedChild(0);
                mUserIdsAdapter.notifyDataSetChanged();
            }
        });
    }

    private void editKey(final ActionMode mode) {
        CryptoOperationHelper.Callback<SaveKeyringParcel, EditKeyResult> editKeyCallback
                = new CryptoOperationHelper.Callback<SaveKeyringParcel, EditKeyResult>() {

            @Override
            public SaveKeyringParcel createOperationInput() {
                return mSkpBuilder.build();
            }

            @Override
            public void onCryptoOperationSuccess(EditKeyResult result) {
                mode.finish();
                result.createNotify(getActivity()).show();
            }

            @Override
            public void onCryptoOperationCancelled() {
                mode.finish();
            }

            @Override
            public void onCryptoOperationError(EditKeyResult result) {
                mode.finish();
                result.createNotify(getActivity()).show();
            }

            @Override
            public boolean onCryptoSetProgress(String msg, int progress, int max) {
                return false;
            }
        };
        mEditKeyHelper = new CryptoOperationHelper<>(1, this, editKeyCallback, R.string.progress_saving);
        mEditKeyHelper.cryptoOperation();
    }

}
