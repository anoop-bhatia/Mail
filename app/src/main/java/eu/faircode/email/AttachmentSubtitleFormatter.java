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

final class AttachmentSubtitleFormatter {
    private AttachmentSubtitleFormatter() {
    }

    static String format(EntityAttachment attachment, boolean debug) {
        StringBuilder sb = new StringBuilder();
        sb.append(attachment.type);
        if (debug) {
            boolean zohoInline = ZohoApi.INLINE_ATTACHMENT_TYPE.equals(attachment.provider_type);
            if (!zohoInline && attachment.cid != null) {
                sb.append(' ').append(attachment.cid);
                if (attachment.related != null)
                    sb.append(' ').append(attachment.related);
                sb.append(' ').append(attachment.section).append('/').append(attachment.sequence);
            }
            if (attachment.isEncryption())
                sb.append(' ').append(attachment.encryption);
        }
        return sb.toString();
    }
}
