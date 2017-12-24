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

package org.mariotaku.twidere.loader.statuses

import android.content.Context
import android.support.annotation.WorkerThread
import org.attoparser.config.ParseConfiguration
import org.attoparser.dom.DOMMarkupParser
import org.mariotaku.commons.parcel.ParcelUtils
import org.mariotaku.microblog.library.*
import org.mariotaku.microblog.library.model.Paging
import org.mariotaku.microblog.library.model.microblog.Status
import org.mariotaku.microblog.library.twitter.TwitterWeb
import org.mariotaku.twidere.alias.MastodonStatus
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.exception.APINotSupportedException
import org.mariotaku.twidere.extension.atto.filter
import org.mariotaku.twidere.extension.atto.firstElementOrNull
import org.mariotaku.twidere.extension.model.api.mastodon.toParcelable
import org.mariotaku.twidere.extension.model.api.toParcelable
import org.mariotaku.twidere.extension.model.isOfficial
import org.mariotaku.twidere.extension.model.makeOriginal
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.model.AccountDetails
import org.mariotaku.twidere.model.ParcelableStatus
import org.mariotaku.twidere.model.pagination.PaginatedArrayList
import org.mariotaku.twidere.model.pagination.PaginatedList
import org.mariotaku.twidere.model.pagination.Pagination
import org.mariotaku.twidere.model.pagination.SinceMaxPagination
import org.mariotaku.twidere.util.database.StatusFilterMatcher
import java.text.ParseException
import java.util.*

class ConversationLoader(
        context: Context,
        status: ParcelableStatus,
        adapterData: List<ParcelableStatus>?,
        fromUser: Boolean,
        loadingMore: Boolean
) : AbsRequestStatusesLoader(context, status.account_key, adapterData, fromUser, loadingMore) {

    override val comparator: Comparator<ParcelableStatus>? = null

    var canLoadAllReplies: Boolean = false
        private set

    private val status = ParcelUtils.clone(status).apply { makeOriginal() }

    @Throws(MicroBlogException::class)
    override fun getStatuses(account: AccountDetails, paging: Paging): PaginatedList<ParcelableStatus> {
        return when (account.type) {
            AccountType.MASTODON -> getMastodonStatuses(account, paging).mapTo(PaginatedArrayList()) {
                it.toParcelable(account)
            }
            else -> getMicroBlogStatuses(account, paging)
        }
    }

    private fun getMastodonStatuses(account: AccountDetails, paging: Paging): List<MastodonStatus> {
        val mastodon = account.newMicroBlogInstance(context, Mastodon::class.java)
        canLoadAllReplies = true
        val statusContext = mastodon.getStatusContext(status.id)
        return statusContext.ancestors + statusContext.descendants
    }

    @Throws(MicroBlogException::class)
    private fun getMicroBlogStatuses(account: AccountDetails, paging: Paging): PaginatedList<ParcelableStatus> {
        canLoadAllReplies = false
        when (account.type) {
            AccountType.TWITTER -> {
                val twitter = account.newMicroBlogInstance(context, Twitter::class.java)
                val isOfficial = account.isOfficial(context)
                canLoadAllReplies = isOfficial
                if (isOfficial) {
                    return twitter.showConversation(status.id, paging).mapMicroBlogToPaginated {
                        it.toParcelable(account, profileImageSize)
                    }
                }
                return showConversationCompat(twitter, account, status, true)
            }
            AccountType.STATUSNET -> {
                val statusNet = account.newMicroBlogInstance(context, StatusNet::class.java)
                canLoadAllReplies = true
                status.extras?.statusnet_conversation_id?.let {
                    return statusNet.getStatusNetConversation(it, paging).mapMicroBlogToPaginated {
                        it.toParcelable(account, profileImageSize)
                    }
                }
            }
            AccountType.FANFOU -> {
                val fanfou = account.newMicroBlogInstance(context, Fanfou::class.java)
                canLoadAllReplies = true
                return fanfou.getContextTimeline(status.id, paging).mapMicroBlogToPaginated {
                    it.toParcelable(account, profileImageSize)
                }
            }
            else -> {
                throw APINotSupportedException("API", account.type)
            }
        }
        canLoadAllReplies = true
        val microBlog = account.newMicroBlogInstance(context, MicroBlog::class.java)
        return showConversationCompat(microBlog, account, status, true)
    }

    @WorkerThread
    override fun shouldFilterStatus(status: ParcelableStatus): Boolean {
        return StatusFilterMatcher.isFiltered(context.contentResolver, status, 0)
    }

    @Throws(MicroBlogException::class)
    private fun showConversationCompat(twitter: MicroBlog, details: AccountDetails,
            status: ParcelableStatus, loadReplies: Boolean): PaginatedList<ParcelableStatus> {
        val statuses = ArrayList<Status>()
        val pagination = this.pagination as? SinceMaxPagination
        val maxId = pagination?.maxId
        val sinceId = pagination?.sinceId
        val maxSortId = pagination?.maxSortId ?: -1
        val sinceSortId = pagination?.sinceSortId ?: -1
        val noSinceMaxId = maxId == null && sinceId == null

        var nextPagination: Pagination? = null

        // Load conversations
        if (maxId != null && maxSortId < status.sort_id || noSinceMaxId) {
            var inReplyToId: String? = maxId ?: status.in_reply_to_status_id
            var count = 0
            while (inReplyToId != null && count < 10) {
                val item = twitter.showStatus(inReplyToId)
                inReplyToId = item.inReplyToStatusId
                statuses.add(item)
                count++
            }
        }
        if (loadReplies || noSinceMaxId || sinceId != null && sinceSortId > status.sort_id) {
            // Load replies
            var repliesLoaded = false
            try {
                if (details.type == AccountType.TWITTER) {
                    if (noSinceMaxId) {
                        statuses.addAll(loadTwitterWebReplies(details, twitter))
                    }
                    repliesLoaded = true
                }
            } catch (e: MicroBlogException) {
                // Ignore
            }
            if (!repliesLoaded) {
//                val query = SearchQuery()
//                query.count(100)
//                if (details.type == AccountType.TWITTER) {
//                    query.query("to:${status.user_screen_name} since_id:${status.id}")
//                } else {
//                    query.query("@${status.user_screen_name}")
//                }
//                query.sinceId(sinceId ?: status.id)
//                try {
//                    val queryResult = twitter.search(query)
//                    val firstId = queryResult.firstOrNull()?.id
//                    if (firstId != null) {
//                        nextPagination = SinceMaxPagination.sinceId(firstId, 0)
//                    }
//                    queryResult.filterTo(statuses) { it.inReplyToStatusId == status.id }
//                } catch (e: MicroBlogException) {
//                    // Ignore for now
//                }
            }
        }
        return statuses.mapTo(PaginatedArrayList()) {
            it.toParcelable(details, profileImageSize)
        }.apply {
            this.nextPage = nextPagination
        }
    }

    private fun loadTwitterWebReplies(details: AccountDetails, twitter: MicroBlog): List<Status> {
        val web = details.newMicroBlogInstance(context, TwitterWeb::class.java)
        val page = web.getStatusPage(status.user_screen_name, status.id).page

        val parser = DOMMarkupParser(ParseConfiguration.htmlConfiguration())
        val statusIds = ArrayList<String>()

        try {
            val document = parser.parse(page)
            val repliesElement = document.firstElementOrNull { element ->
                element.getAttributeValue("data-component-context") == "replies"
            } ?: throw MicroBlogException("No replies data found")
            repliesElement.filter {
                it.getAttributeValue("data-item-type") == "tweet" && it.hasAttribute("data-item-id")
            }.mapTo(statusIds) { it.getAttributeValue("data-item-id") }
        } catch (e: ParseException) {
            throw MicroBlogException(e)
        }
        if (statusIds.isEmpty()) {
            throw MicroBlogException("Invalid response")
        }
        return twitter.lookupStatuses(statusIds.distinct().toTypedArray())
    }
}

