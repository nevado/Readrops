package com.readrops.app.repositories

import com.readrops.api.services.Credentials
import com.readrops.api.services.SyncType
import com.readrops.api.services.greader.GReaderDataSource
import com.readrops.api.services.greader.GReaderSyncData
import com.readrops.api.utils.AuthInterceptor
import com.readrops.app.util.Utils
import com.readrops.db.Database
import com.readrops.db.entities.Feed
import com.readrops.db.entities.Folder
import com.readrops.db.entities.Item
import com.readrops.db.entities.ItemState
import com.readrops.db.entities.Tag
import com.readrops.db.entities.TagJoin
import com.readrops.db.entities.account.Account
import com.readrops.db.pojo.ItemContent
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class GReaderRepository(
    database: Database,
    account: Account,
    private val dataSource: GReaderDataSource,
) : BaseRepository(database, account), KoinComponent {

    override suspend fun login(account: Account) {
        val authInterceptor = get<AuthInterceptor>().apply {
            credentials = Credentials.toCredentials(account)
        }

        account.token = dataSource.login(account.login!!, account.password!!)
        // we got the authToken, time to provide it to make real calls
        authInterceptor.credentials = Credentials.toCredentials(account)

        account.writeToken = dataSource.getWriteToken()

        val userInfo = dataSource.getUserInfo()
        account.displayedName = userInfo.userName
    }

    override suspend fun synchronize(
        selectedFeeds: List<Feed>,
        onUpdate: suspend (Feed) -> Unit
    ): Pair<SyncResult, ErrorResult> = throw NotImplementedError("This method can't be called here")

    override suspend fun synchronize(): SyncResult {
        val itemStateChanges = database.itemStateChangeDao()
            .selectItemStateChanges(account.id)

        val syncData = GReaderSyncData(
            readIds = itemStateChanges.filter { it.readChange && it.read }
                .map { it.remoteId },
            unreadIds = itemStateChanges.filter { it.readChange && !it.read }
                .map { it.remoteId },
            starredIds = itemStateChanges.filter { it.starChange && it.starred }
                .map { it.remoteId },
            unstarredIds = itemStateChanges.filter { it.starChange && !it.starred }
                .map { it.remoteId }
        )

        val syncType: SyncType
        if (account.lastModified != 0L) {
            syncType = SyncType.CLASSIC_SYNC
            syncData.lastModified = account.lastModified
        } else {
            syncType = SyncType.INITIAL_SYNC
        }

        val newLastModified = System.currentTimeMillis() / 1000L

        return dataSource.synchronize(syncType, syncData, account.writeToken!!).run {
            insertFolders(folders)
            val newFeeds = insertFeeds(feeds)
            val tags = insertTags(tags)

            val (newItems, updatedItems) = insertItems(items, false)
            insertItems(starredItems, true)

            insertItemsTags(newItems, updatedItems, tags)

            insertItemsIds(unreadIds, readIds, starredIds.toMutableList())

            account.lastModified = newLastModified
            database.accountDao().updateLastModified(newLastModified, account.id)

            database.itemStateChangeDao().resetStateChanges(account.id)

            SyncResult(
                items = newItems,
                feeds = newFeeds
            )
        }
    }

    override suspend fun insertNewFeeds(
        newFeeds: List<Feed>,
        onUpdate: (Feed) -> Unit
    ): ErrorResult {
        val errors = hashMapOf<Feed, Exception>()

        for (newFeed in newFeeds) {
            onUpdate(newFeed)

            try {
                dataSource.createFeed(account.writeToken!!, newFeed.url!!, newFeed.remoteFolderId)
            } catch (e: Exception) {
                errors[newFeed] = e
            }
        }

        return errors
    }

    override suspend fun updateFeed(feed: Feed) {
        dataSource.updateFeed(account.writeToken!!, feed.url!!, feed.name!!, feed.remoteFolderId!!)
        super.updateFeed(feed)
    }

    override suspend fun deleteFeed(feed: Feed) {
        dataSource.deleteFeed(account.writeToken!!, feed.url!!)
        super.deleteFeed(feed)
    }

    override suspend fun updateFolder(folder: Folder) {
        dataSource.updateFolder(account.writeToken!!, folder.remoteId!!, folder.name!!)
        folder.remoteId = GReaderDataSource.FOLDER_PREFIX + folder.name

        super.updateFolder(folder)
    }

    override suspend fun deleteFolder(folder: Folder) {
        dataSource.deleteFolder(account.writeToken!!, folder.remoteId!!)
        super.deleteFolder(folder)
    }

    private suspend fun insertFeeds(feeds: List<Feed>): List<Feed> {
        feeds.forEach { it.accountId = account.id }
        return database.feedDao().upsertFeeds(feeds, account)
    }

    private suspend fun insertFolders(folders: List<Folder>) {
        folders.forEach { it.accountId = account.id }
        database.folderDao().upsertFolders(folders, account)
    }

    private suspend fun insertTags(tags: List<Tag>): List<Tag> {
        return database.tagDao().upsertTags(tags.map { it.copy(accountId = account.id) }, account)
    }

    /**
     * Insert new items and update the content of the ones already known locally.
     *
     * Since FreshRSS 1.29.0, the `ot` parameter also returns items modified since the last sync,
     * with the same id, so they must not be inserted again.
     *
     * @return the inserted items and the updated items
     */
    private suspend fun insertItems(
        items: List<Item>,
        starredItems: Boolean
    ): Pair<List<Item>, List<Item>> {
        val newItems = arrayListOf<Item>()
        val updatedItems = arrayListOf<Item>()
        val itemsFeedsIds = mutableMapOf<String?, Int>()

        // the same item can't be returned twice by the API, but better safe than sorry
        val distinctItems = items.distinctBy { it.remoteId }
        val existingItemIds = distinctItems.mapNotNull { it.remoteId }
            .chunked(MAX_SQL_PARAMETERS)
            .flatMap { remoteIds ->
                database.itemDao().selectItemIdsByRemoteIds(remoteIds, account.id).entries
            }
            .associate { it.key to it.value }

        for (item in distinctItems) {
            val feedId: Int
            if (itemsFeedsIds.containsKey(item.feedRemoteId)) {
                feedId = itemsFeedsIds.getValue(item.feedRemoteId)
            } else {
                feedId =
                    database.feedDao().selectRemoteFeedLocalId(item.feedRemoteId!!, account.id)
                itemsFeedsIds[item.feedRemoteId] = feedId
            }

            item.feedId = feedId

            if (item.text != null) {
                item.readTime = Utils.readTimeFromString(item.text!!)
            }

            val existingId = existingItemIds[item.remoteId]
            if (existingId != null) {
                item.id = existingId
                updatedItems += item
                continue
            }

            // workaround to avoid inserting starred items coming from the main item call
            // as the API exclusion filter doesn't seem to work
            if (!starredItems) {
                if (!item.isStarred) {
                    newItems.add(item)
                }
            } else {
                newItems.add(item)
            }
        }

        if (newItems.isNotEmpty()) {
            newItems.sortWith(Item::compareTo)
            database.itemDao().insert(newItems)
                .zip(newItems)
                .forEach { (id, item) -> item.id = id.toInt() }
        }

        if (updatedItems.isNotEmpty()) {
            database.itemDao().updateItemsContent(updatedItems.map { ItemContent(it.id, it) })
        }

        return newItems to updatedItems
    }

    private suspend fun insertItemsTags(
        newItems: List<Item>,
        updatedItems: List<Item>,
        allTags: List<Tag>
    ) {
        // updated items tags are replaced by the ones coming from the API
        updatedItems.map { it.id }
            .chunked(MAX_SQL_PARAMETERS)
            .forEach { database.tagJoinDao().deleteByItemIds(it) }

        (newItems + updatedItems).associate { it to it.tags }
            .flatMap { (item, tags) ->
                tags
                    .filter { tag -> allTags.any { tag.remoteId == it.remoteId } }
                    .map { tag ->
                        TagJoin(
                            itemId = item.id,
                            tagId = allTags.first { tag.remoteId == it.remoteId }.id,
                        )
                    }
            }
            .run {
                database.tagJoinDao().insert(this)
            }
    }

    private suspend fun insertItemsIds(
        unreadIds: List<String>,
        readIds: List<String>,
        starredIds: MutableList<String> // TODO is it performance wise?
    ) {
        database.itemStateDao().deleteItemStates(account.id)

        database.itemStateDao().insert(unreadIds.map { id ->
            val starred = starredIds.any { starredId -> starredId == id }

            if (starred) {
                starredIds.remove(id)
            }

            ItemState(
                id = 0,
                read = false,
                starred = starred,
                remoteId = id,
                accountId = account.id
            )
        })

        database.itemStateDao().insert(readIds.map { id ->
            val starred = starredIds.any { starredId -> starredId == id }
            if (starred) {
                starredIds.remove(id)
            }

            ItemState(
                id = 0,
                read = true,
                starred = starred,
                remoteId = id,
                accountId = account.id
            )
        })

        // insert starred items ids which are read
        if (starredIds.isNotEmpty()) {
            database.itemStateDao().insert(starredIds.map { id ->
                ItemState(
                    0,
                    read = true,
                    starred = true,
                    remoteId = id,
                    accountId = account.id
                )
            })
        }
    }

    companion object {
        // stay under SQLite bound parameters limit (999 before SQLite 3.32)
        private const val MAX_SQL_PARAMETERS = 500
    }
}