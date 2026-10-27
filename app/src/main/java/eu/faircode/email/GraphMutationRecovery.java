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

/** Decisions for Graph writes whose server outcome is uncertain. */
final class GraphMutationRecovery {
    enum MissingMessageAction {
        KEEP_QUARANTINED,
        REFRESH_SOURCE_AND_TARGET_DELTAS
    }

    private GraphMutationRecovery() {
    }

    static boolean requiresReconciliation(EntityOperation operation, String unknownOutcomeError) {
        return (operation != null && (operation.state != null ||
                unknownOutcomeError.equals(operation.error)));
    }

    static MissingMessageAction afterMessageLookupFailure(EntityOperation operation, int status) {
        if (operation != null && EntityOperation.MOVE.equals(operation.name) && status == 404)
            return MissingMessageAction.REFRESH_SOURCE_AND_TARGET_DELTAS;
        return MissingMessageAction.KEEP_QUARANTINED;
    }
}
