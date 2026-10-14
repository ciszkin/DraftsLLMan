# DraftsLLMan

**A zero-coordinate, multi-agent engine that turns text and sketches into constraint-correct SVG.**

The LLM never emits a single coordinate. It states *relational intents* — `align`, `distance_ratio`,
`connect`, `contain`, `distribute`, `relative_size` — and a Cassowary constraint solver computes the
exact geometry. That is the whole trick: LLMs are bad at arithmetic and great at relationships, so
the drawings come out aligned and non-overlapping instead of collapsed into a blob.

```
"draw a bicycle"
    ↓
Architect (text LLM) → emit_skeleton → [front-wheel brief, frame brief, rear-wheel brief, …]
    ↓
Draftsman 1 → component (solved, isolated scene)   ┐
Draftsman 2 → component                            ├─ merge (offset + namespace)
Draftsman 3 → component                            ┘
    ↓
<svg> — one composed drawing
```

There is also a second, vision-first entry point: drop in a **raster sketch** and a vision agent
recovers the structure through the same solver (`sketch/`).

## Quick start

```bash
# JDK 21 is auto-provisioned by Gradle (Foojay resolver) — whatever `java` is on PATH doesn't matter.
cp local.properties.example local.properties   # then set your provider's keys (gitignored)
./gradlew run --args='"a circle centered above a rectangle"'
```

Prints the SVG to stdout and writes `output/<slug>-<epoch>.svg` + a full LLM/tool trace to
`output/trace.log`. Requires a configured provider (networked).

## Provider configuration

`local.properties` (gitignored — it holds real API keys) selects one of three providers via
`drafts.provider`; every key has a `DRAFTS_*` env-var override. See `local.properties.example`.

| Provider | `drafts.provider` | Keys | Notes |
|---|---|---|---|
| Ollama (local, default) | `ollama` | `ollama.endpoint`, `ollama.model`, optional `ollama.numctx` | Needs a tools+vision model |
| OpenRouter (cloud) | `openrouter` | `openrouter.apikey`, `openrouter.model` | OpenAI-compatible |
| OpenCode Go (cloud) | `opencode` | `opencode.apikey`, `opencode.model`, `opencode.visionmodel` | `qwen3.8-flash` is text-only there — vision requests go to `opencode.visionmodel` (e.g. `kimi-k3`) |

## Tests

```bash
./gradlew test             # 453 deterministic tests — no network, headless
./gradlew cleanTest test   # force re-run (Gradle skips unchanged test tasks)
./gradlew test --tests "by.ciszkin.draftsllman.solver.ConstraintSolverTest"
```

Opt-in live/external tests (skipped unless the env var is set):

| Gate | What it does |
|---|---|
| `DRAFTS_LIVE_SMOKE=1` | live Ollama chat / Text-to-SVG / vision smoke |
| `DRAFTS_CAPTURE=1` | re-capture recorded-replay fixtures into `src/test/resources` |
| `DRAFTS_OPENCODE_SMOKE=1` | OpenCode Go provider smoke |
| `DRAFTS_COMFY_SMOKE=1` / `DRAFTS_COMFY_GENERATE=1` | live LAN ComfyUI sketch generation / materialize the dev slice |
| `DRAFTS_SKETCH_EVAL=1` | the S1 decision-gate evaluation (see below) |

## Architecture

```
src/main/kotlin/by/ciszkin/draftsllman/
├── Main.kt          # CLI entry point
├── scene/           # immutable SceneNode graph + SVG export — the single source of truth
├── solver/          # Cassowary constraint solver, named anchors, Safe-Harbor anti-collapse
├── geometry/        # GeometryEngine — anchor/preset geometry (patterns, boundaries)
├── engine/          # ToolExecutor + Memento snapshot/rollback transactions
├── contract/        # JSON-schema ValidationGate for every LLM tool call
├── render/          # headless Skia rasterizer (foveated: global + ROI crops)
├── agent/           # Koog agent loop, vision strategy, reasoning tolerance, tracing
├── architect/       # decompose prompt → skeleton → ComponentBriefs
├── draftsman/       # draw one brief in an isolated scene
├── coordination/    # Coordinator: Architect → Draftsmen → merge into one global SVG
├── library/         # Prototype+Override component library (built; pipeline wiring is future work)
├── sketch/          # S1: vision-first raster → solved SceneGraph
└── eval/            # M1 evaluation harness (seeded fixtures + semantic decision gate)
```

### Design invariants

- **Zero coordinates.** The LLM emits relational intents; the Cassowary solver computes geometry.
  No runtime snapping exists — the only rounding is 2-decimal formatting at SVG export.
- **Every LLM→engine call is a validated transaction** — JSON-schema-validated args, snapshot
  before, rollback on failure. Undo is never an inverse function.
- **Sanitized output.** IDs, text, and exported SVG pass through `SvgSanitizer` (no
  `<script>`, no `<foreignObject>`).
- **Koog's `maxAgentIterations` counts node executions, not LLM calls** — behavior is injected by
  replacing nodes 1:1, never by growing the loop.

## The M1 evaluation harness

Synthetic, seeded, and honest: `WireframeDSL` builds a clean wireframe **and** co-derives its ground
truth; `Sketchify`/`ComfyUISketchify` rasterize it into a hand-drawn-looking sketch; `SketchRunner`
(vision agent) recovers the structure; `SemanticScorer` scores it. Two tests in the normal suite
materialize preview fixtures under `output/` for eyeballing.

The go/no-go gate for the vision-first approach is **semantic**, not pixel-based:
`F1sem ≥ 0.80` (role+kind recovery) **and** `relCons ≥ 0.70` (coarse relative layout preserved).
Run it live:

```bash
DRAFTS_COMFY_GENERATE=1 ./gradlew test --tests "by.ciszkin.draftsllman.eval.ComfyLiveTest"  # materialize slice
DRAFTS_SKETCH_EVAL=1     ./gradlew test --tests "by.ciszkin.draftsllman.eval.SketchEvaluationTest"  # score it
```

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.3.21 (JVM 21) |
| Agent orchestration | JetBrains Koog 1.1.1 |
| Constraint solving | kasuari-kotlin 0.1.6 (Cassowary) |
| Rendering | Skia via Skiko 0.9.18 (headless) |
| Serialization | kotlinx-serialization 1.11.0 + JSON-schema validation |
| Providers | Ollama · OpenRouter · OpenCode Go |

## License

[MIT](LICENSE)
