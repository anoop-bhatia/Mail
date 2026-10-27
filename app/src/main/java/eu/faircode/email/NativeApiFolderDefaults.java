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

/** Creation-only system-folder sync and navigation defaults for new native API accounts. */
final class NativeApiFolderDefaults {
    private NativeApiFolderDefaults() {
    }

    static boolean shouldSynchronizeByDefault(String type) {
        return EntityFolder.shouldSynchronize(type);
    }

    static void applyCreationDefaults(EntityFolder folder, boolean newApiAccount, boolean folderCreated) {
        if (folder != null && newApiAccount && folderCreated) {
            boolean synchronize = shouldSynchronizeByDefault(folder.type);
            folder.synchronize = synchronize;
            folder.navigation = synchronize;
        }
    }

    static boolean shouldQueueInitialSync(boolean newApiAccount, boolean folderCreated,
                                          EntityFolder folder) {
        return (newApiAccount && folderCreated && folder != null &&
                Boolean.TRUE.equals(folder.synchronize));
    }
}
