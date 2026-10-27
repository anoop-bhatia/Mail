package eu.faircode.email;

/*
    This file is part of FairEmail.
    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.
    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

/** Default display names used only when inserting new native REST accounts. */
final class NativeApiAccountNaming {
    private NativeApiAccountNaming() {
    }

    static String forNewAccount(int authType, String email) {
        if (email == null)
            return null;
        String prefix;
        if (authType == ServiceAuthenticator.AUTH_TYPE_GMAIL)
            prefix = "Gmail";
        else if (authType == ServiceAuthenticator.AUTH_TYPE_ZOHO)
            prefix = "Zoho Mail";
        else if (authType == ServiceAuthenticator.AUTH_TYPE_GRAPH_API)
            prefix = "Microsoft Mail";
        else
            return null;
        int at = email.indexOf('@');
        String localPart = (at < 0 ? email : email.substring(0, at));
        return prefix + "/" + localPart;
    }
}
