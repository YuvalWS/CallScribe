package app.callscribe.detect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.util.Log
import app.callscribe.rules.ContactGroupIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads contact groups from the phone's address book. Samsung groups and Google Contacts labels both appear as
 * Android contact groups. Needs READ_CONTACTS; without it everything is empty and group policies don't apply.
 * Nothing leaves the phone.
 */
class ContactGroupsReader(private val context: Context) {

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Group id → title, for user groups (skips deleted groups and auto-add ones such as "My Contacts"). */
    private fun groups(): Map<Long, String> = buildMap {
        context.contentResolver.query(
            Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE),
            "${Groups.DELETED} = 0 AND ${Groups.AUTO_ADD} = 0", null, null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.takeIf { it.isNotBlank() }?.let { put(c.getLong(0), it) } }
    }

    /** Distinct group titles, for the "add group policy" picker. */
    suspend fun titles(): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        runCatching { groups().values.distinct().sortedBy { it.lowercase() } }.getOrElse { Log.w(TAG, "groups", it); emptyList() }
    }

    suspend fun index(): ContactGroupIndex = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ContactGroupIndex.EMPTY
        runCatching { build() }.getOrElse { Log.w(TAG, "index", it); ContactGroupIndex.EMPTY }
    }

    private fun build(): ContactGroupIndex {
        val titles = groups()
        if (titles.isEmpty()) return ContactGroupIndex.EMPTY
        val r = context.contentResolver

        val groupsOf = mutableMapOf<Long, MutableSet<String>>()
        r.query(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, GroupMembership.GROUP_ROW_ID),
            "${Data.MIMETYPE} = ?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null,
        )?.use { c ->
            while (c.moveToNext()) titles[c.getLong(1)]?.let { groupsOf.getOrPut(c.getLong(0)) { mutableSetOf() } += it }
        }
        if (groupsOf.isEmpty()) return ContactGroupIndex.EMPTY

        val names = mutableMapOf<Long, String>()
        r.query(Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in groupsOf) c.getString(1)?.let { names[id] = it }
            }
        }
        val numbers = mutableMapOf<Long, MutableList<String>>()
        r.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in groupsOf) c.getString(1)?.let { numbers.getOrPut(id) { mutableListOf() } += it }
            }
        }
        return ContactGroupIndex.of(groupsOf.map { (id, g) -> Triple(names[id].orEmpty(), numbers[id].orEmpty(), g) })
    }

    private companion object { const val TAG = "ContactGroups" }
}
