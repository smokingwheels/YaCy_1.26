package net.yacy.htroot.api.blacklists;

import java.util.Locale;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.data.ListManager;
import net.yacy.data.WorkTables;
import net.yacy.repository.BlacklistHelper;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class add_entry_p {

    private static final String RESULT_FAILURE = "0";
    private static final String RESULT_SUCCESS = "1";
    private static final String XML_ITEM_STATUS = "status";
    private static final String KEY_NEW_ENTRY = "item";
    private static final String KEY_CURRENT_BLACKLIST = "list";

    public static serverObjects respond(final RequestHeader header, final serverObjects post, @SuppressWarnings("unused") final serverSwitch env) {

        final serverObjects prop = new serverObjects();

        if (post!= null && post.containsKey(KEY_CURRENT_BLACKLIST) && post.containsKey(KEY_NEW_ENTRY)) {

            final String blacklistToUse = post.get(KEY_CURRENT_BLACKLIST, "").trim();
            final String entry = post.get(KEY_NEW_ENTRY, "").trim();

            ListManager.switchboard.tables.recordAPICall(
            		post,
            		"add_entry_p." + header.fileType().toString().toLowerCase(Locale.ROOT),
            		WorkTables.TABLE_API_TYPE_CONFIGURATION,
					"add to blacklist '" + blacklistToUse + "': " + entry);

            if (BlacklistHelper.addBlacklistEntry(blacklistToUse, entry)) {
                prop.put(XML_ITEM_STATUS, RESULT_SUCCESS);

                Switchboard.urlBlacklist.clear();
                ListManager.reloadBlacklists();
            } else {
                prop.put(XML_ITEM_STATUS, RESULT_FAILURE);
            }

        } else {
            prop.put(XML_ITEM_STATUS, RESULT_FAILURE);
        }

        return prop;
    }

}
