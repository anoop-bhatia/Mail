# Microsoft Graph attachment size and `$value` integrity

## Root cause

FairEmail previously compared the local `$value` response file length to the attachment resource's `size` field. Microsoft Graph documents a case where those counts differ, so a successful transfer could be rejected after HTTP 200.

Microsoft's [large-attachment guide](https://learn.microsoft.com/en-us/graph/outlook-large-attachments) provides a concrete file-attachment example: the upload session is initialized with the source file size **3,483,322 bytes**, and its raw `/$value` example returns `Content-Length: 3,483,322`; the later attachment metadata response reports `size: 3,640,066`. Whatever the cause of that additional metadata accounting, Graph's own example shows that `size` is not a reliable exact byte count for the raw file response.

The [base attachment resource](https://learn.microsoft.com/en-us/graph/api/resources/attachment?view=graph-rest-1.0) and [fileAttachment resource](https://learn.microsoft.com/en-us/graph/api/resources/fileattachment?view=graph-rest-1.0) define `size` as the attachment's size in bytes. Separately, Graph documents that `/$value` returns raw file bytes for a file attachment, while an [itemAttachment](https://learn.microsoft.com/en-us/graph/api/resources/itemattachment?view=graph-rest-1.0) `/$value` response is a MIME serialization of the attached contact, event, or message. The item metadata size is not specified as the length of that MIME serialization. Graph can also omit `size`; FairEmail's parser already represents that as `null`.

## Fix and safety checks

Only Graph `fileAttachment` and `itemAttachment` downloads treat provider `size` as advisory. Their final payload must still be a real file, must be non-empty, and must stay under the existing cache size limit. Their cache reuse follows the same rule. Other providers and unclassified Graph attachment types retain strict equality checks; Graph `referenceAttachment` remains unsupported as a local file.

The Graph transfer uses `HttpsURLConnection` in `GraphMailApi`, not Retrofit. The file path requests `Accept-Encoding: identity`, compares copied bytes with HTTP `Content-Length` when that header describes the delivered representation, and skips that comparison if a non-identity `Content-Encoding` is present. A mismatch or read error deletes the temporary response; it is never atomically installed. If `Content-Length` is absent, transport/framing I/O errors still fail the request and empty payloads are rejected, but there is no independent exact byte count to compare against.

Regression tests cover Microsoft's documented metadata/raw-byte counts, absent Graph size metadata, item/file/reference classification, successful HTTP transfer-length validation, and rejection/cleanup of an HTTP-declared short response.
