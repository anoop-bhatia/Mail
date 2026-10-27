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

package eu.faircode.email;

/** Timeout policy for the native Microsoft Graph mail REST paths only. */
final class GraphTimeouts {
    static final int CONNECT_MILLIS = 30_000;
    static final int GET_RESPONSE_MILLIS = 30_000;
    static final int MUTATION_RESPONSE_MILLIS = 60_000;
    static final int MUTATION_WRITE_MILLIS = 60_000;
    static final int DIRECT_SEND_RESPONSE_MILLIS = 90_000;
    static final int DRAFT_CREATE_RESPONSE_MILLIS = 60_000;
    static final int DRAFT_SEND_RESPONSE_MILLIS = 90_000;
    static final int DRAFT_DELETE_RESPONSE_MILLIS = MUTATION_RESPONSE_MILLIS;
    static final int UPLOAD_SESSION_CREATE_RESPONSE_MILLIS = 60_000;
    static final int UPLOAD_SESSION_STATUS_RESPONSE_MILLIS = 60_000;
    static final int UPLOAD_CHUNK_RESPONSE_MILLIS = 90_000;
    static final long MAX_OVERALL_SEND_MILLIS = 5L * 60L * 1000L;
    static final long MAX_OVERALL_UPLOAD_FLOW_MILLIS = 300_000L;

    private GraphTimeouts() {
    }
}
