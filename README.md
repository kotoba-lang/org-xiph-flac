# kotoba-lang/org-xiph-flac

Zero-dependency portable `.cljc` **FLAC** decoder (xiph.org's format
specification).

```clojure
(require '[flac.core :as flac])

(flac/flac? bytes)       ; signature sniff
(flac/stream-info bytes) ; sample rate, channels, bit depth, total samples, MD5
(flac/metadata bytes)    ; every metadata block, VORBIS_COMMENT tags included
(flac/decode bytes)      ; => {:channels [[…] […]] :sample-rate … :bits … :samples n}
```

FLAC is lossless, so there is exactly one right answer: `decode` returns the
original integer samples, per channel, exactly. Samples are signed integers at
the stream's own bit depth — not normalised — the same convention as
`org-microsoft-riff`.

## What is implemented

All four subframe types (CONSTANT, VERBATIM, FIXED orders 0-4, LPC orders 1-32),
both Rice residual methods with escaped partitions, wasted-bits subframes, all
three stereo decorrelations (left/side, right/side, mid/side), every block-size
and sample-rate coding including the explicit 8- and 16-bit forms, and
STREAMINFO / VORBIS_COMMENT metadata with the remaining block types reported by
type, offset and length.

## Not implemented

**No encoder.** Choosing predictor orders and Rice parameters well is most of
what libFLAC does; a naive encoder would be larger and slower than the reference
at every level while adding a class of only-our-decoder-reads-it bugs.

**The STREAMINFO MD5 is recorded, not verified** — there is no MD5 in this
workspace, and a codec is the wrong place to add one. Frame CRC-8/CRC-16 fields
are read past rather than checked; corruption still surfaces, because a damaged
frame fails structurally (the suite flips a bit and asserts it is caught).

Also absent: Ogg-encapsulated FLAC (`org-xiph-ogg` identifies it, and the
mapping's header would need unwrapping first), seeking, and streaming — whole
buffer in, whole buffer out, bounded by `:max-samples`.

## Traps this format sets

- **The frame number is UTF-8-*like*, extended to 36 bits** — seven bytes, well
  past the four Unicode allows. A decoder written against modern UTF-8 rejects a
  legal frame beyond 2^31 samples.
- **The side channel of a decorrelated pair carries one extra bit**, because a
  difference needs the range. Which channel it is depends on the assignment.
- **Mid/side hides a low bit**: the mid channel is stored shifted, and the side
  channel's parity restores it.
- **The first partition of a residual is shorter by the predictor order**, and
  only the first.
- **LPC prediction needs a floor division**, not a truncation — `quot` rounds
  toward zero and produces wrong samples on negative predictions only, so a
  suite that tests a rising waveform passes.

## Test

```sh
clojure -M:test        # JVM: portable suite + the `flac` binary, sample for sample
clojure -M:local:test  # …against sibling checkouts
nbb run-tests.cljs     # ClojureScript: the portable suite, recorded fixtures
clojure -M:lint
```

The portable fixtures carry **the reference decoder's own output**, so
ClojureScript asserts bit-exactness rather than "it decoded something". The JVM
suite adds the breadth a recording cannot: **every compression level 0-8** over
four sources chosen so each subframe type occurs, three bit depths (8/16/24),
mono and stereo, four block sizes, a sample rate outside the frame header's
table, a 3-second file, and an encoder-written VORBIS_COMMENT.

Regenerate the fixtures with:

```sh
nbb tools/record_fixtures.cljs
```
