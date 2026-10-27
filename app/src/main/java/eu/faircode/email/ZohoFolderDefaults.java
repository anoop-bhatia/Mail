package eu.faircode.email;
/*
    This file is part of FairEmail.
    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
    You should have received a copy of the GNU General Public License
    along with FairEmail. If not, see <http://www.gnu.org/licenses/>.
    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import java.util.Locale;

/** Maps Zoho system-folder names to FairEmail folder types. */
final class ZohoFolderDefaults {
    private ZohoFolderDefaults() {
    }

    static String getFolderType(String remoteName) {
        String name = (remoteName == null ? "" : remoteName.trim().toLowerCase(Locale.ROOT));
        switch (name) {
            case "inbox":
                return EntityFolder.INBOX;
            case "drafts":
                return EntityFolder.DRAFTS;
            case "sent":
            case "sent items":
                return EntityFolder.SENT;
            case "trash":
            case "bin":
            case "deleted items":
                // Zoho accounts may expose this system folder as Trash or Bin;
                // both use FairEmail's local Trash type while keeping the remote name.
                return EntityFolder.TRASH;
            case "spam":
            case "junk":
                return EntityFolder.JUNK;
            case "archive":
                return EntityFolder.ARCHIVE;
            default:
                return EntityFolder.USER;
        }
    }
}
