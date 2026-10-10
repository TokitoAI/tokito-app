# Dataset-backed board knowledge

The reference repository is `TokitoAI/tokito-app-dataset`. Source documents are
evidence, not instructions. Embeddings rank records; similarity does not
establish factual or physical confidence. Qwen remains the local generator.

## Compilation and admission

`tools/ingest_board_knowledge.py` reads a pinned commit and actual engineering
inventories. It preserves source path/hash/locator, board scope, reference,
schematic units, original values and DNP state. Dirty source content, unresolved
LFS pointers, ambiguous inventories and invalid identifiers fail. Output is an
immutable SQLite database containing source JSON and normalized 384-dimensional
MiniLM vectors, plus a corpus and digest report. Atomic publication cannot
overwrite an existing database. Python is host tooling, not the Android path.

The compiler never manufactures photo labels, placement coordinates, reviewed
physical identities or component-node mappings. The current requested sources:

- `esp32_board`: community reference clone; exact mapping unverified.
- `esp8266_board`: different USB/UART NodeMCU variant; exact mapping unverified.
- `nxp_frdm_mcxn236_board`: model matches silkscreen, physical revision unconfirmed.
  MCXN947 is not a substitute.
- `ultimainboard`: Ultimainboard 2.1.4 source revision matches the marking, but
  pixel-to-PCB registration remains required.

Preserve source-specific license notes. Download access is not redistribution
permission; NXP and Ultimaker materials have separate terms. Compilation reports
`distribution_admitted: false`. A release must separately admit rights and sign
the corpus inventory. Expected digests supplied by arbitrary user input are not
trusted package admission.

## Native reasoning boundary

`BoardKnowledgeStore` opens a bounded read-only database against expected
artifact/corpus/encoder digests supplied by an admitted package. It does not
verify signatures or grant rights itself. Searches filter board scope before
ranking. Missing board data is empty retrieval, not a cross-board neighbour.

`LocalBoardRag` orchestrates background embedding, scoped retrieval and actual
`TokitoDialogueRuntime` Qwen requests. Board/corpus/reference-scope bindings are
checked before and after asynchronous work. The provider must expose an atomic
native scope snapshot, not unchecked model output. A scope can be an explicitly
selected catalog reference; it does not assert that a physical board has been
identified. Query and corpus encoders match. Text/audio reference answers do not
require camera tracking, AR registration, GLBs or component highlighting.

Qwen returns only a `facts` array of zero to four existing source fact IDs.
Native validation rejects invented IDs, duplicates, extra keys/prose and
malformed output. Spoken values/citations are reconstructed from source records.
The selector has no component-target or rendering authority; physical targeting
requires the independently admitted detector and assembly context.
The bounded prompt exposes at most 40 scalar facts, at most 240 characters each;
excluded fields cannot be claimed as read. Full source records remain stored.

The bench loads `knowledge.manifest.json` and `knowledge.sqlite` from the signed
Knowledge search (embedding) bundle, alongside the matching encoder. Its signed
inventory admits both; an arbitrary database copied into storage is not used.
Naming a catalog alias in a question selects its reference scope; ambiguous
names request clarification. No camera, GLB, Cloud session or manual registration
is required for reference questions. Bounded conversation memory is reset when
the selected reference changes or the bench loses foreground ownership. The
actual local Qwen selects source facts and validated source text reaches local
speech; it does not generate unchecked electrical claims. Imported authored
project evidence retains its separate board-target authority. Automatic
physical-board selection and AR highlighting have separate detector and geometry
gates; their absence must not disable reference-scoped text answers. Unit tests
cannot substitute for device qualification.

The compiler also imports optional source-reviewed `knowledge/reference-facts.json`
from the pinned dataset commit, retaining its hash, fact locator and primary-source
URL. Overview facts and aliases are corpus data, never board-specific code branches.
Current aggregate source redistribution rights remain unresolved. Private device
evaluation uses an explicitly labelled private-evaluation license; public builds
reject it. Do not relabel a mixed corpus as MIT to bypass that admission gate.

## Detector and 3D handoff

Anandhu's signed detector package must map model classes to catalog board IDs,
variants/revisions and component labels. Generic MCU is not a reference
designator. Reference mapping requires reviewed evidence, calibrated identity
thresholds and registration landmarks. Unsupported/ambiguous variants abstain.

Navaneeth's GLB uses the existing digital-twin asset contract: GLB 2, metres,
right-handed Y-up, matching immutable assembly-context revision and exact node
names (`componentNodePrefix` plus reference designator). Authored context supplies
placement, side, orientation and landmarks. Separate selectable nodes are
required; a board-only mesh cannot support component highlighting.

The local selector accepts a single JSON object, optionally enclosed by one
whole-response JSON code fence. This is presentation normalization only: prose
outside the fence, extra objects/keys, unknown fact IDs, conflicting targets and
unsupported claims still fail closed. Spoken answers are composed from validated
retrieved source values, not arbitrary generated engineering claims.

The join is catalog board/variant -> reviewed assembly context -> designator ->
component GLB node. Knowledge IDs are `board_id:reference_designator`, separating
reused references. The AR controller resolves the designator through admitted
context and a fresh canonical frame graph. Clear highlights on identity,
revision or registration loss. Highlight unavailability is separate from audio
answer availability. Qwen/vector search never invent nodes or coordinates.

Small offline corpora use exact cosine retrieval instead of a network service
or approximate index. See primary
[Sentence Transformers guidance](https://www.sbert.net/examples/sentence_transformer/applications/semantic-search/README.html).
Change the index only after corpus/device measurements justify it.
