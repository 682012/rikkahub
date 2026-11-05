# Responses API file outputs — reference representation (r20)

Scope: **`ai` module only** (OpenAI Responses provider parsers/adapters + data model if genuinely needed).
No app/UI/shared-media edits here. Handoff for the app side is in [`RESPONSES-HANDOFF.md`](RESPONSES-HANDOFF.md).

## 1. Problem

`code_interpreter` (and `file_search`) artifacts arrive as **citations**, not as items with inline bytes:

```jsonc
// item: {"type":"message","content":[{"type":"output_text","text":"...","annotations":[ ... ]}]}
{ "type": "container_file_citation",
  "container_id": "cntr_68a1...", "file_id": "cfile_68a1...",
  "filename": "chart.png", "start_index": 41, "end_index": 60 }

{ "type": "file_citation",          // vector store / file_search
  "file_id": "file-abc123", "filename": "annual-report.pdf", "index": 41 }

{ "type": "url_citation",           // web_search / web fetch
  "url": "https://...", "title": "...", "start_index": 0, "end_index": 12 }
```

The model writes `sandbox:/mnt/data/chart.png` in its prose and attaches the citation above.
The bytes live in the Responses container and are fetched **only** through the API:

```
GET {baseUrl}/containers/{container_id}/files/{file_id}/content      (Authorization: Bearer …)
```

Before r20 the parser dropped every annotation type except `url_citation`, and for Responses it did not
even read `message.content[].annotations`, so generated GIF/PNG/CSV from `code_interpreter` was silently
discarded and `sandbox:/mnt/data/...` in the text was a dead link.

## 2. Decision

Two separate concepts, never conflated:

| concept | representation | where |
| --- | --- | --- |
| *what the model cited* | `UIMessageAnnotation.FileCitation` / `.ContainerFileCitation` (metadata only, **no bytes, no auth**) | `UIMessage.annotations` |
| *an artifact the app can open* | `UIMessagePart.Document` with a **provider-less secure reference** in `url` + `ResponsesFileOutputMetadata` in `metadata` | `UIMessage.parts` |

### 2.1 Annotation (provenance only)

```kotlin
@Serializable @SerialName("file_citation")
data class FileCitation(
    val title: String,          // filename
    val fileId: String,
    val containerId: String? = null,
    val startIndex: Int = -1,   // -1 = unknown; file_citation uses `index`
    val endIndex: Int = -1,
) : UIMessageAnnotation()

@Serializable @SerialName("container_file_citation")
data class ContainerFileCitation(
    val containerId: String,    // required by the schema
    val fileId: String,
    val title: String = "",     // filename
    val startIndex: Int = -1,
    val endIndex: Int = -1,
) : UIMessageAnnotation()
```

`startIndex`/`endIndex` are real character offsets in the output text, carried so the app can later
anchor the citation inside the text. Existing `UIMessageAnnotation.UrlCitation` is untouched, so current
citation UI keeps working; the new subclasses are additive for `kotlinx.serialization` (existing JSON
still decodes — covered by `UIMessageAnnotationTest`).

### 2.2 Document part (openable artifact)

```kotlin
UIMessagePart.Document(
    url = "rkresponses-file://container?container_id=cntr_%E2%80%A6&file_id=cfile_%E2%80%A6", // see 2.3
    fileName = "chart.png",
    mime = "image/gif",                     // best guess from filename; app must sniff bytes
    metadata = ResponsesFileOutputMetadata(
        protocol        = "openai_responses",
        kind            = "container_file", // "container_file" | "file"
        containerId     = "cntr_…",         // null for vector-store `file_citation`
        fileId          = "cfile_…",
        filename        = "chart.png",
        annotationStartIndex = 41,          // optional, citation anchor in the assistant text
        annotationEndIndex   = 60,
        source          = "code_interpreter", // best-effort owning item type; not a capability claim
    ).toMetadata()
)
```

`ResponsesFileOutputMetadata` lives in `MessageMetadata.kt` with the other typed metadata and follows the
same nullable-field / stable-key rules (old sessions must still decode).

### 2.3 Why `rkresponses-file://` and not an HTTPS URL

The content endpoint needs `Authorization: Bearer <api key>`. A URL placed in a persisted message is
copied into logs, exports, web DTOs and `persistHistoricalMedia`, so the API key must never be part of
it. The reference is therefore an **opaque, non-fetchable handle**:

- scheme `rkresponses-file`, host `container` (Responses container files) or `file` (vector store files);
- query params `container_id` (optional) and `file_id` — exactly what the content endpoint needs;
- **no** host, **no** token, **no** path any HTTP client would resolve on its own.

It is deliberately not `http(s)`, so `UIMessagePart.Document` cannot be opened by the OS directly and
nothing leaks when a user taps it before the app has resolved it. Helpers in
`ai/src/main/java/me/rerere/ai/ui/ResponsesFileReferences.kt` build/parse it:

```kotlin
fun responsesContainerFileReference(containerId: String, fileId: String): String
fun responsesStoreFileReference(fileId: String): String
fun parseResponsesFileReference(url: String): ResponsesFileReference?   // null if not ours
data class ResponsesFileReference(val kind: Kind, val containerId: String?, val fileId: String)
```

### 2.4 Who does what

- **ai (this change)**: recognize both citation types in **streaming and non-streaming**, emit the
  annotation plus a `Document` part with the safe reference, dedupe (same `container_id`+`file_id` →
  one document, one annotation), never invent bytes and never emit `sandbox:/mnt/data` as a link.
- **app (main, see handoff)**: for each `Document` whose URL is a `rkresponses-file://` reference, fetch
  `GET {baseUrl}/containers/{cid}/files/{fid}/content` with the *configured provider's* credentials,
  validate the media (magic bytes / size cap), store it via `FilesManager`, then replace the part URL
  with the local file. Only then does the artifact become openable.
- `image_generation_call` output (base64 `result`) is unchanged and stays `UIMessagePart.Image`.
  `code_interpreter_call` `outputs[].url` (when a gateway sends one) stays inside the existing
  `ServerTool` as plain JSON, never auto-fetched.

## 3. Honest capability statement

`BuiltInTools.CodeInterpreter` only means "send `{"type":"code_interpreter","container":{"type":"auto"}}`
in the request". The parsers recognize response shapes **by shape**, not by provider brand, because
several gateways reshape or drop container endpoints. **No claim is made here that the pure backend
supports a working code-interpreter artifact round-trip until main runs an actual probe** (handoff §4).
What is implemented and tested here is streaming + non-streaming parsing of a recognized shape.

## 4. Dedupe rules (streaming)

- `response.output_text.annotation.added` may fire once per annotation, and `response.output_item.done`
  / `response.completed` may repeat the same annotations. The decoder keeps a per-response
  `container_id|file_id` seen-set, so a citation yields *one* `StreamChunk.Annotations` payload and
  *one* `StreamChunk.FileArtifact` per response.
- The same key also suppresses a *repeat* annotation inside one `StreamChunk.Annotations` batch.
- `StreamChunkHandler` appends artifacts by key (idempotent) and keeps the existing
  `annotations = (annotations + chunk.annotations).distinct()` dedupe.
- Text is never rewritten: the `Document` part is appended after the text it belongs to, so prose still
  reads "…saved chart.png" with a file chip below instead of a dead `sandbox:` link.

## 5. Streaming event added (additive only)

```kotlin
@Serializable @SerialName("file_artifact")
data class FileArtifact(val id: String, val document: UIMessagePart.Document) : StreamChunk()
```

No existing `StreamChunk` member changes shape. `id` = `container_id|file_id` (or `file_id`), so the
handler can key it.

## 6. Tests

- `ResponseApiFileOutputTest` — non-streaming: container/file citation → one annotation + one Document
  with `rkresponses-file://` URL, no `sandbox:` link, no duplicate, `image_generation_call` still an Image,
  no empty placeholders.
- `ResponseApiStreamDecoderFileOutputTest` — streaming: `annotation.added` then `output_item.done` with
  the same citation yields a single artifact/annotation; text parts unaffected; finish ordering sane.
- `UIMessageAnnotationTest` — legacy `url_citation` JSON still decodes; new types round-trip.
- `ResponsesFileReferencesTest` — reference build/parse, rejects http/file/data URLs and foreign schemes.
