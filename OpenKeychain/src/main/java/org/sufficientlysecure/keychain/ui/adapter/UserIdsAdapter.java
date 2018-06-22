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

package org.sufficientlysecure.keychain.ui.adapter;


import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ViewAnimator;

import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.provider.KeychainContract.Certs;
import org.sufficientlysecure.keychain.service.SaveKeyringParcel;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils.State;

public class UserIdsAdapter extends UserAttributesAdapter {
    protected LayoutInflater mInflater;
    private SaveKeyringParcel.Builder mSkpBuilder;
    private boolean mShowStatusImages;

    public UserIdsAdapter(Context context, Cursor c, int flags, boolean showStatusImages) {
        super(context, c, flags);
        mInflater = LayoutInflater.from(context);

        mShowStatusImages = showStatusImages;
    }

    public UserIdsAdapter(Context context, Cursor c, int flags) {
        this(context, c, flags, true);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        TextView vName = view.findViewById(R.id.user_id_item_name);
        TextView vAddress = view.findViewById(R.id.user_id_item_address);
        TextView vComment = view.findViewById(R.id.user_id_item_comment);
        ImageView vVerified = view.findViewById(R.id.user_id_item_certified);
        ViewAnimator vVerifiedLayout = view.findViewById(R.id.user_id_icon_animator);

        ImageView vDeleteButton = view.findViewById(R.id.user_id_item_delete_button);
        vDeleteButton.setVisibility(View.GONE); // not used

        String userId = cursor.getString(INDEX_USER_ID);
        String name = cursor.getString(INDEX_NAME);
        String email = cursor.getString(INDEX_EMAIL);
        String comment = cursor.getString(INDEX_COMMENT);
        if (name != null) {
            vName.setText(name);
        } else {
            vName.setText(R.string.user_id_no_name);
        }
        if (email != null) {
            vAddress.setText(email);
            vAddress.setVisibility(View.VISIBLE);
        } else {
            vAddress.setVisibility(View.GONE);
        }
        if (comment != null) {
            vComment.setText(comment);
            vComment.setVisibility(View.VISIBLE);
        } else {
            vComment.setVisibility(View.GONE);
        }

        boolean isPrimary = cursor.getInt(INDEX_IS_PRIMARY) != 0;
        boolean isRevoked = cursor.getInt(INDEX_IS_REVOKED) > 0;

        // for edit key
        if (mSkpBuilder != null) {
            String changePrimaryUserId = mSkpBuilder.getChangePrimaryUserId();
            boolean changeAnyPrimaryUserId = (changePrimaryUserId != null);
            boolean changeThisPrimaryUserId = (changeAnyPrimaryUserId && changePrimaryUserId.equals(userId));
            boolean revokeThisUserId = (mSkpBuilder.getMutableRevokeUserIds().contains(userId));

            // only if primary user id will be changed
            // (this is not triggered if the user id is currently the primary one)
            if (changeAnyPrimaryUserId) {
                // change _all_ primary user ids and set new one to true
                isPrimary = changeThisPrimaryUserId;
            }

            if (revokeThisUserId) {
                if (!isRevoked) {
                    isRevoked = true;
                }
            }

            vVerifiedLayout.setDisplayedChild(2);
        } else {
            vVerifiedLayout.setDisplayedChild(mShowStatusImages ? 1 : 0);
        }

        if (isRevoked) {
            // set revocation icon (can this even be primary?)
            KeyFormattingUtils.setStatusImage(mContext, vVerified, null, State.REVOKED, R.color.key_flag_gray);

            // disable revoked user ids
            vName.setEnabled(false);
            vAddress.setEnabled(false);
            vComment.setEnabled(false);
        } else {
            vName.setEnabled(true);
            vAddress.setEnabled(true);
            vComment.setEnabled(true);

            if (isPrimary) {
                vName.setTypeface(null, Typeface.BOLD);
                vAddress.setTypeface(null, Typeface.BOLD);
            } else {
                vName.setTypeface(null, Typeface.NORMAL);
                vAddress.setTypeface(null, Typeface.NORMAL);
            }

            int isVerified = cursor.getInt(INDEX_VERIFIED);
            switch (isVerified) {
                case Certs.VERIFIED_SECRET:
                    KeyFormattingUtils.setStatusImage(mContext, vVerified, null, State.VERIFIED, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
                case Certs.VERIFIED_SELF:
                    KeyFormattingUtils.setStatusImage(mContext, vVerified, null, State.UNVERIFIED, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
                default:
                    KeyFormattingUtils.setStatusImage(mContext, vVerified, null, State.INVALID, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
            }
        }
    }

    public boolean getIsRevokedPending(int position) {
        mCursor.moveToPosition(position);
        String userId = mCursor.getString(INDEX_USER_ID);

        boolean isRevokedPending = false;
        if (mSkpBuilder != null) {
            if (mSkpBuilder.getMutableRevokeUserIds().contains(userId)) {
                isRevokedPending = true;
            }

        }

        return isRevokedPending;
    }

    /** Set this adapter into edit mode. This mode displays additional info for
     * each item from a supplied SaveKeyringParcel reference.
     *
     * Note that it is up to the caller to reload the underlying cursor after
     * updating the SaveKeyringParcel!
     *
     * @see SaveKeyringParcel
     *
     * @param saveKeyringParcel The parcel to get info from, or null to leave edit mode.
     */
    public void setEditMode(@Nullable SaveKeyringParcel.Builder saveKeyringParcel) {
        mSkpBuilder = saveKeyringParcel;
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.view_key_adv_user_id_item, null);
    }
}
