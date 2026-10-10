# Architecture facts

The embedded Physical shell must not run R8 independently: its public classes
are called by the Expo bridge in another module. Shrink only at the final APK
boundary, propagating consumer rules from native libraries.

**Why:** independent library shrinking removed ModelBundleRepository from a
successful release build and failed when opening the phone's model manager.

**How to apply:** keep library `isMinifyEnabled = false` and check the installed
release's bridge calls as well as build/unit checks.

The optimized final APK and its release instrumentation APK share a narrow
binary acceptance boundary in `apps/mobile/proguard-release-abi.pro`, applied
unconditionally by the Expo plugin. Keep production R8 enabled; do not create
a less-optimized test target or disable shrinking to get a passing benchmark.
**Why:** AndroidTest applies the target name mapping but cannot reconstruct
R8-reordered method parameters or narrowed constructor types. Intrinsics and
the explicitly exercised runtime exports must keep their original signatures.
Build the target and test APK together, install both exact outputs with `-r`,
and run the installed runner directly to preserve admitted app-private models.

GenieX resolves plugins from `ApplicationInfo.nativeLibraryDir`, so the unified
APK needs `expo.useLegacyPackaging=true` (native library extraction). A library
module's packaging setting does not control the final Expo APK.

Speech uses the signed model installer alongside dialogue and vision. Its
payload is `models/voice/active/bundle`; manual legacy voice-models directories
are not a runtime fallback. Installation validates files/configuration; device
inference must be checked separately. Evaluation-only model licenses require
the explicit local build property documented in `physical-runtime/docs/VOICE_RUNTIME.md`.

Local conversation answers general electronics questions without a loaded board
reference. Board-specific identities still require verified context; model output
is not a measured or visually verified fact. Keep this distinction in the dialogue
protocol and the native bench entry point.

Shared Cloud conversation history and generation run journals are server-owned.
Phone chat opens at the latest turn, but only a reader drag can disable live
scroll following. Earlier-message pagination preserves the reading anchor;
the Latest action restores following.
Async history hydration follows measured content and viewport sizes rather
than FlatList's estimated last-row geometry. Preserve visible-item anchors
only while reading older messages: keeping that anchor during hydration can
override the initial latest-message scroll. Footer-only streamed output must
also follow even when there are no saved rows yet.
Streamed output belongs to its exact run ID: stop the previous watch before
queueing a new user message, and ignore
late events for older runs. Tool-separated response rounds preserve completed
prefaces instead of replacing or duplicating them. Render assistant Markdown
from tokens into native components, never HTML/WebView execution or remote
image loads. Prepared-change cards navigate to the existing server-backed
Review flow; a card is not evidence that changes were applied.
Assistant cards stretch to the available chat width; only user bubbles are
right-aligned and width-bounded. Markdown documents contribute their natural
height (`flexShrink: 0`), while list text flexes horizontally inside each row.
**Why:** an intrinsically sized card plus a shrinking vertical document made
long bullet lists narrow and allowed their text to overflow the rounded card.
Ordinary project chat uses the conversational planner regardless of schematic
revision, matching desktop. Existing geometry does not turn a question into a
prepared edit: the schematic/BOM editor agents are only for explicit edit flows.
**Why:** selecting the edit-only agent from revision alone prevented normal
circuit reasoning and estimates in phone conversations.
Mobile review sends selected operation indices against the server-captured
proposal, never a replacement schematic or a local suggestion-status mutation.
Approval/rejection command IDs and bodies are persisted before transmission;
uncertain responses replay the exact command. A receipt is successful only when
its run/event identity and all selected applied/unchanged indices match. Stale or
invalid proposals are blocked for explicit review, not silently rebased.

Account-wide review replay uses oldest-first pending commands, not the UI's
newest-first display list. An uncertain command holds subsequent commands in
the same project/design scope while unrelated scopes continue. Authentication
rejection stops the entire account worker. Chat replay likewise continues past
unavailable independent threads; missing threads and proposals become explicit
blocked items rather than infinite retries. Retrying an uncertain transmission
must preserve its exact durable command body and IDs.
Definitively rejected conversation creation quarantines both the pending thread
and its dependent draft in one transaction. Keep the unconfirmed conversation
visible after restart. Explicit draft recovery retains its creation ID/body and
unblocks that creation; transient network/rate-limit failures retry unchanged.
New-conversation first sends use one native transaction for the thread, message,
and immutable command receipt. The UI retains a command UUID and its original
revision until receipt delivery; a background revision update does not silently
change an uncertain command. Exact retries return original thread/message/run
IDs even after acknowledgement. Changed intent conflicts. Confirmed deletion
removes receipt content but retains a tombstone so retries cannot resurrect it.
Receipt storage is bounded and fails closed rather than evicting retry identity.
Run watchers wait locally while the message is queued and surface quarantined
messages without requesting nonexistent runs. Once acknowledged, an actual
server run snapshot plus durable event replay recovers acceptance which preceded
the UI's receipt; never synthesize a running status to fill that race.
**Why:** a project-specific outage must not starve another project's work, and
display sorting must never silently reorder authoritative decisions.

Multi-sheet chat explicitly selects a canonical synchronized sheet. A single
sheet can be inferred from the actual document; multiple sheets must never
default to the first entry. The chosen sheet ID is persisted with the queued
message, so retries cannot change its edit context.

The native conversation session persists message IDs and streamed events before
delivery; closing a viewer does not cancel a run. Only the explicit cancel API
requests cancellation. The bridge shutdown method is named `shutdown`, not
`close`: UniFFI already generates Kotlin `close()` for object disposal.

Cloud chat caches and sync outboxes are scoped to the verified account and API
origin; project-filtered sync journals additionally get independent cache paths.
Unbound legacy sync files must not be replayed under a newly signed-in account.
**Why:** sharing a cache across accounts can send a previous user's pending
commands with a different bearer token. Never derive cache identity from an
unverified UI email, and never use the bearer token as a persistent cache key.

Project connection status describes the project-update transport, not chat
availability or snapshot freshness. Published project indexes and journal
resources are reconciled deterministically; journal tombstones must not be
resurrected by a late index response. Missing update dates stay unknown, never
"just now". A durable local enqueue is not a server commit: only confirmed
journal events replace visible synchronized resources.

Project detail reads the canonical server journal even when no legacy published
snapshot exists. Canonical BOM `lines` reference `parts` by `part_id`; join those
records and matching schematic references before rendering, never cast them to
legacy `ref`/`value` rows. Quantities are floating-point through Rust, UniFFI and
TypeScript so fractional material quantities are not truncated or invented.

HTTP acknowledgements retain a durable snapshot lower bound separately from
the SSE replay cursor. A bootstrap older than that bound is rejected even if
it omits the acknowledged resource. Never advance the replay cursor from an
HTTP receipt: that would skip unrelated preceding events. A resource revision
has immutable content; contradictory same-revision payloads fail atomically.

Sync startup emits the account-scoped persisted resources before connecting.
The workspace activates a native ownership lease synchronously before starting
any asynchronous worker. Queue, conversation, review, and sync startup require
that exact owner; matching credentials alone cannot acquire or replace ownership.
Account changes invalidate the old lease before IO, and worker installation is
guarded atomically after IO. A late startup or authorization failure from a retired
workspace cannot stop the current account. Only workspace disposal deactivates
the lease; callback changes and transport retries do not create new ownership.
All signed-in routes retain the same workspace provider, including model
management; navigation must not stop project synchronization. Cold start restores
the verified session automatically. Transient identity/startup failures retry on
foreground and a bounded timer; authentication and malformed-protocol failures do
not retry indefinitely. Native SSE/outbox workers own online recovery after startup.
Device registration and a consistent bootstrap must succeed before the outbox
or SSE worker starts. Network/service outages retry cancellably, keeping queued
commands intact; protocol/authentication failures stop instead of inventing a
successful connection. Cached account restore is specified in `auth-contract.md`.

**How to apply:** scope native callbacks to their subscription, reject older
resource revisions, guard late HTTP responses after navigation, and preserve
the published snapshot's provenance. Never approximate schematic symbols with
generic boxes to make an unavailable preview appear successful.

The current schematic viewer uses the revision-bound canonical preview endpoint,
not legacy published artifacts or getProject success. Rust verifies project,
design, sheet, revision and SHA-256 before passing SVG to the inert image viewer.
The UI chooses actual synchronized sheets, rejects late responses after navigation,
and labels a retained older drawing stale while requesting the new revision.

Verified previews persist in a bounded SQLite cache (16 drawings, 32 MiB),
scoped to the server-verified account and API origin, project, design, sheet and
exact revision. Reopening requires an unexpired verified identity binding and
revalidates the SVG digest; an authorization rejection revokes that binding.
The canonical source digest is immutable for a cached revision. Authenticated
responses may replace derived SVG bytes with a validated renderer update only
when that source digest is unchanged; renderer upgrades are not document edits.
Cached content is labelled saved, never presented as proof of a live connection.
Revision updates replace the inert image while preserving normalized pan/zoom;
changing design or sheet resets the viewport. An unavailable BOM is not an empty
BOM: show unknown state until canonical or successful published data is present.

**Why:** routing every utterance through the board-reference index disabled general
conversation even when the local LLM was installed and healthy.

Voice barge-in detection starts with audio playback, not TTS generation. Cancelling
generation intentionally completes as interrupted speech rather than a synthesis
failure. Both phone and sw600dp layouts reserve system-inset space for voice controls.
The React Native shell uses one SafeAreaProvider/SafeAreaView boundary for all
routes; individual headers must not guess Android status-bar or cutout heights.

The Expo/React Native shell owns ordinary product UI and the existing Rust
UniFFI Cloud client. Native Android owns CameraX, AR, voice, local models,
physical tools, and their lifecycle.

**Why:** high-rate media and inference cannot safely or efficiently traverse a
JavaScript bridge.

**How to apply:** bridge only versioned capabilities, bounded commands,
low-rate lifecycle state, typed observations, and failures. Frames, audio,
tensors, and high-rate tracking stay native.

Physical sessions are locally authoritative in `PhysicalSessionStore`.
Stable session, observation, and evidence IDs plus optimistic session revisions
are persisted before the native bench opens. A record enters Cloud sync only
after explicit per-record authorization. The Expo control plane merges complete
structured snapshots into the versioned `physical_sessions` resource after the
Rust outbox durably accepts them. Projectless sessions remain local; evidence
sync contains metadata and digests, never content URIs or media bytes.

The production Android artifact has one launcher and one package. Expo owns
`dev.tokito.app`; neither predecessor reached production, so no legacy upgrade
identity is carried forward. The imported Physical shell is an internal Android
library/activity and has no launcher or application identity of its own.

**How to apply:** link the Physical modules through
`apps/mobile/plugins/withTokitoPhysical.js`, open the internal activity through
the `tokito-physical` Expo module, and verify the combined APK in CI. The
minimum Android SDK is 27. LiteRT is pinned to 1.4.1 because Expo 57's Pika
compiler supports Kotlin 2.1.x while LiteRT 2.2 metadata requires Kotlin 2.3;
do not upgrade either side independently without building the unified APK.

Android generated resources must pass the final application release lint gate,
not only the native-library gate. The Physical integration plugin qualifies
Android 13's splash behavior in `values-v33` after Expo finishes generating its
styles, preserving the full splash theme. Camera hardware is optional at the
manifest level; Cloud project review must remain installable without a camera.
**How to apply:** maintain these transformations in the Expo source plugin,
never patch only ignored generated Android files or suppress API-level errors.

