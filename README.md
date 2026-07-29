# loop-jiten

The continuous orchestrator for [`jiten`](https://github.com/kotoba-lang/jiten):
it ingests sourced encyclopedia entries, runs a cycle over them, and keeps an
append-only record of what each cycle actually saw.

It owns **order and evidence**. It does not own scoring truth — admissibility,
link semantics, and every statistic come from `jiten`, and nothing here
recomputes them. That split is the `loop-*` contract in the workspace taxonomy
(`manifest/repository-rules.edn`, ADR-2607299000), and it exists so a second,
drifting definition of "is this entry admissible" cannot grow inside the
orchestrator.

Same division as `innen` ⊣ `loop-innen`.

## The first corpus

`corpus/workspace-2026-07-29.edn` — 8 entries, 25 statements, 16 distinct
sources, **every statement `:documented`**, 0 red links, 0 warnings.

Every claim in it is mechanically derived from a record read at ingest time:

| statement | derived from |
|---|---|
| "`X` is registered … at path `P`, pinned at commit `C`" | `manifest/west.yml` @ a named superproject commit |
| "The GitHub repository `O/X` is public/private and its default branch is `B`" | GitHub API `repos/O/X`, with the retrieval date |
| "ADR-NNNN (status: accepted, DATE) records: «title»" | the ADR file itself |

Nothing is paraphrased. A repo's description is **the title of an accepted
ADR** — itself a record with a status and a date — not a sentence someone typed
into the seed file. `resources/workspace-seeds.edn` names repos and ADR ids and
contains no prose about any of them, deliberately: the moment a human writes
"kotobase is a fast graph database" into a seed, that sentence has no source and
the whole record is worth less.

## Fail-closed, in four places

Each is from the `innen` experience (ADR-2607258500) rather than from a design
document:

1. **A seeded repo missing from `west.yml` is an error, not a skip.** Silently
   dropping it would make a deregistered repo look like one that was never
   seeded.
2. **`:expect` must match the resolved path.** A repo that moved orgs would
   otherwise be written up under its old identity.
3. **A declared ADR that cannot be read is an error.** Emitting the entry
   anyway produces one whose only claim is "it is registered" — which reads as a
   complete entry rather than a failed read.
4. **The corpus is re-parsed before the script exits 0.** `innen` once shipped a
   corpus that printed fine and could not be read back (`:node/1973-oil-crisis`
   — a keyword may not begin with a digit). Printing is not proof.

A GitHub read that fails is *reported* (count + reason on stderr) rather than
swallowed. A failed read and a repo with nothing to say produce the same corpus,
so the difference has to be stated somewhere or it is not a difference.

`observe` applies the same rule to corpus files: an unparsable or wrong-shaped
file lands in `:skipped` with its reason and is printed in the report. A corpus
that quietly shrank because one file stopped parsing looks exactly like a corpus
that was always that size.

## Cycle

```
observe          read every corpus/*.edn; report what could not be read
evaluate         hand the entries to jiten; keep what it says
decide           rank the gaps — see below
act              write target/loop-jiten-report.md
record-evidence  append exactly one line to ledger/loop-jiten-ledger.edn
```

`decide` produces two lists, because they are different kinds of debt:

- **`:write-next`** — targets other entries point at but nothing defines, ranked
  by how many entries want them. That is the corpus stating its own priority
  instead of a human guessing at coverage.
- **`:corroborate-next`** — entries whose every claim rests on one source. Not a
  fault (a registry-backed entry legitimately rests on its registry) but it is
  the shape a fabricated entry has.

## Run

```bash
# derive the corpus from the workspace's own records
nbb --classpath "../jiten/src:src" scripts/ingest_workspace.cljs \
    --root /path/to/superproject           # [--no-github] [--as-of YYYY-MM-DD]

# one cycle: report + one ledger line
nbb --classpath "../jiten/src:src" bin/run.cljs

# tests
nbb --classpath "../jiten/src:src:test" test/run_tests.cljs   # 7 tests, 23 assertions
```

## The ledger is not a document

`ledger/loop-jiten-ledger.edn` is append-only and must not be rewritten.
ADR-2607257000 makes workspace documents rewritable in place and names ledgers
as the exception; this is one. It is the record of what each cycle *observed*,
and editing a past line destroys the only thing it is for: seeing that coverage
went up, or that a source stopped being cited, without taking this cycle's word
for it.

`target/loop-jiten-report.md` is the opposite — a derived artifact. Regenerate
it, never edit it.

## License

MIT
