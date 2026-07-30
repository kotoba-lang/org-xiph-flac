# CLAUDE.md — org-xiph-flac

FLAC decoding, portable `.cljc`, zero runtime dependencies.

## Invariants

- **No host codec in `src/`.** The `flac` and `ffmpeg` binaries appear in
  `test/flac/flac_oracle_test.clj` and `tools/record_fixtures.cljs` only.
  `org-microsoft-riff` is a *test* dependency: the reference decoder emits WAV.
- **Bit-exactness is the assertion.** FLAC is lossless; "decoded without
  throwing" proves nothing. Every fixture carries the reference's own samples.
- **`test/flac/fixtures.cljc` is generated** — `nbb tools/record_fixtures.cljs`.
- **Decoding only.** No encoder; say so rather than shipping a bad one.
- **Every failure is an `ex-info` with a `:reason`.**
- **Both runtimes are gated** (`clojure -M:test`, `nbb run-tests.cljs`).

## Traps

- **`Math/pow` returns a double on the JVM.** A double where an integer is
  expected fails loudly there (`Argument must be an integer: 246.0`) and passes
  silently under ClojureScript, where every number is a double. Every power of
  two goes through `bits/pow2`, which is exact on both. This broke seven JVM
  tests while nbb was green.
- **Side-effecting reads inside a map literal are not sequenced.** A map with
  more than eight entries becomes a hash-map, and under SCI its value forms are
  *not* evaluated in written order — a bit reader threaded through them returns
  shuffled fields. STREAMINFO came back with sample rate 315 (the min frame size)
  until every read moved into a `let`. Never put a reader call in a map literal.
- **LPC needs floor division** (`bits/floor-div`), not `quot`. Truncation toward
  zero is wrong only for negative predictions, so a rising test waveform hides it.
- **The frame number is UTF-8 extended to 36 bits** — seven bytes.
- **The side channel of a decorrelated pair has one extra bit**, and which
  channel that is depends on the assignment (8/9/10).
- **Mid/side stores the mid channel shifted**, with the side channel's parity
  carrying the dropped low bit.
- **Only the first residual partition is shortened by the predictor order.**

## Layout

| namespace | role |
|---|---|
| `flac.core` | metadata blocks, frames, subframes, residuals, stereo decorrelation, `decode` |
| `flac.bits` | MSB-first reader, unary/Rice helpers, the UTF-8-like frame number, `pow2`/`floor-div` |
| `tools/record_fixtures.cljs` | regenerates the fixtures, reference samples included |

## If an encoder is ever added

Do the Rice parameter search and the fixed predictors first; LPC coefficient
estimation is where the real work is. The bar is not "the reference decodes it"
but "the ratio is in the reference's league at the same level", the way
`org-sourceware-bzip2` states it.
