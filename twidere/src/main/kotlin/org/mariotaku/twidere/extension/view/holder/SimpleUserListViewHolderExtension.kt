/*
 *             Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.extension.view.holder

import android.view.View
import com.bumptech.glide.RequestManager
import org.mariotaku.twidere.R
import org.mariotaku.twidere.extension.loadProfileImage
import org.mariotaku.twidere.extension.model.getBestProfileImage
import org.mariotaku.twidere.model.ParcelableUserList
import org.mariotaku.twidere.util.UserColorNameManager
import org.mariotaku.twidere.view.holder.SimpleUserListViewHolder

fun SimpleUserListViewHolder.display(userList: ParcelableUserList, getRequestManager: () -> RequestManager,
        userColorNameManager: UserColorNameManager, displayProfileImage: Boolean) {
    nameView.text = userList.name
    createdByView.text = createdByView.context.getString(R.string.created_by,
            userColorNameManager.getDisplayName(userList, false))
    if (displayProfileImage) {
        profileImageView.visibility = View.VISIBLE
        val context = itemView.context
        getRequestManager().loadProfileImage(context, userList.getBestProfileImage(context)).into(profileImageView)
    } else {
        profileImageView.visibility = View.GONE
    }
}