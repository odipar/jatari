# Jatari Codebase Analysis

## Overview

**Jatari** is a Java 25 application that plays and exports
[YM format](https://en.wikipedia.org/wiki/YM_(file_format)) Atari ST chiptune
music and Hatari PSG register-capture files.  It drives a cycle-accurate,
VHDL-derived simulation of the
[AY-3-8910 / YM2149](https://en.wikipedia.org/wiki/General_Instrument_AY-3-8910)
sound chip, renders audio through `javax.sound.sampled`, and provides a Swing
GUI for interactive playback and WAV export.

---

## Package Structure

```
org.jatari
├── main          – entry points (Start, StartPsg, StartDemo)
├── atari         – mixers (YmMixer, LinearMixer)
├── ym            – YM-format pipeline
│   ├── format    – file data model, parser, and source processor
│   │   ├── YmFile              – parsed YM data record
│   │   ├── YmFileParser        – supports YM3!/YM4!/YM5!/YM6!, LZH-5 decompression
│   │   └── YmFileProcessor     – jaust source Processor (15 signals @ frame rate Hz)
│   ├── Ym2149Processor         – jaust Processor that drives chip sim (3 INT @ 2 MHz)
│   └── Ym2149SimulationState   – shared mutable chip state (base class)
├── psg           – PSG capture pipeline
│   ├── PsgCapture              – parsed capture data record
│   ├── PsgCaptureParser        – parses Hatari *.csv.zip captures
│   ├── PsgCaptureProcessor     – jaust source Processor (3 signals @ 2 MHz)
│   └── PsgYm2149Processor      – drives chip sim from capture events (3 INT @ 2 MHz)
└── player        – Swing GUI and audio output
    ├── AbstractPlayer          – playback lifecycle, filter chain, WAV export
    ├── YmPlayer / PsgPlayer    – format-specific pipeline builders
    ├── AbstractPlayerApp       – shared Swing scaffolding
    └── YmPlayerApp / PsgPlayerApp – format-specific app subclasses
```

**External dependencies**

| Library | Purpose |
|---------|---------|
| `org.jaust:jaust` | Signal-processing framework (see §jaust below) |
| `org.jm2149.vhdl:jm2149_vhdl` | Cycle-accurate YM2149/AY-3-8910 chip simulation transpiled from VHDL |
| JUnit 5 | Unit tests |

---

## Audio Pipeline

The full rendering pipeline from file to speaker:

```
YmFileProcessor (50 Hz, 15 signals)
  ──[jaust resample / zero-stuff]──►
    Ym2149Processor (2 MHz, 3 INT: chA / chB / chC DAC indices 0–31)
      ──►  YmMixer (2 MHz, 1 INT: signed 16-bit mixed sample)
             ──► [optional ButterworthLowPass  @ 2 MHz, IIR]
                   ──► [optional ButterworthHighPass @ 2 MHz, IIR]
                         ──► box-filter downsample to 44 100 Hz (INT signal)
                               ──► SourceDataLine (16-bit LE mono PCM)
```

For the PSG path the source differs but the tail (mixer → filter → line) is identical:

```
PsgCaptureProcessor (2 MHz, 3 signals)
  ──► PsgYm2149Processor (2 MHz, 3 INT)
        ──► (same tail as YM path)
```

---

## Usage of jaust

[jaust](https://github.com/odipar/jaust) is a lightweight, Faust-inspired
signal-processing framework for Java.  Core abstractions:

| jaust concept | Role in jatari |
|---------------|----------------|
| `Context` / `DefaultContext(freq)` | Defines the sample-rate domain of a processor |
| `Processor` | Composable processing unit with typed input/output `Signal` arrays |
| `Signal` (BOOL / INT / LONG / DOUBLE) | Lazy, indexed stream of values |
| `ctx.genB/genI/genL/genD(LongFunction)` | Source generators from lambdas |
| `ctx.par(Processor...)` | Parallel composition (Faust `,`) |
| `ctx.resample(source)` | Rate conversion: upsamples (zero-stuff) or downsamples (box-filter) |
| `DefaultProcessor` | Convenience interface providing `apply()` → `apply(SignalArray.empty())` |
| `ButterworthLowPass` / `ButterworthHighPass` | IIR filter processors (from jaust.filter) |

**How jatari uses jaust:**

1. **Source processors** – `YmFileProcessor` and `PsgCaptureProcessor` build
   zero-input `Processor` instances using `ctx.genI`/`ctx.genB` and `ctx.par`.
   Register data is accessed lazily via array-index lookups, enabling streaming
   without pre-allocation of the full output signal.

2. **Rate conversion** – `Ym2149Processor.apply()` calls
   `context.resample(source)` to upsample from 50 Hz (frame rate) to 2 MHz
   (chip clock).  The jaust `UpProcessor` zero-stuffs: BOOL write-enable is
   `true` only at the original frame boundaries, `false` elsewhere.  This is
   exploited by `SimulationState.writeRegisters()` to avoid spurious register
   writes between frames.  The PSG path skips resampling because its source
   already runs at 2 MHz.

3. **Stateful chip integration** – `Ym2149SimulationState` holds the mutable
   `Ym2149AudioIndexed` chip instance and implements purely sequential,
   monotonically-advancing evaluation.  This is the one place where jaust's
   lazy/random-access model is violated by design: querying a past sample
   returns the cached value instead of rewinding.

4. **Filter chain** – `AbstractPlayer.buildFilterChain()` wires
   `ButterworthLowPass` and `ButterworthHighPass` from jaust.filter into the
   2 MHz domain before the box-filter downsample step.

5. **Signal wrapping** – Several places wrap plain `IntSignal`/`DoubleSignal`
   anonymous classes around intermediate values (e.g. the `SequentialDoubleCache`
   inside `buildFilterChain`) to bridge the jaust lazy-signal API with imperative
   audio-rendering loops.

---

## Pros

### Design & architecture

- **Clean separation of concerns** – parsing, chip simulation, mixing, filtering,
  and UI live in distinct packages with well-defined interfaces.
- **Reusable base classes** – `AbstractPlayer` and `AbstractPlayerApp` avoid
  duplication between the YM and PSG paths, following the Template Method
  pattern effectively.
- **Shared chip-simulation state** – `Ym2149SimulationState` factors out
  ~60 lines of loop/latch logic shared by both `Ym2149Processor` (YM path) and
  `PsgYm2149Processor` (PSG path), eliminating copy–paste.
- **Record types** – `YmFile` and `Ym2149Processor` (as a record) make the
  data model concise and immutable where appropriate.
- **Self-contained decompressor** – `Lzh5Decompressor` handles LZH-5 archives
  without a third-party library, keeping the dependency footprint small.
- **Good documentation** – public classes and methods carry Javadoc with
  pipeline diagrams, usage snippets, and links to related types.

### Signal processing

- **Accurate resampling** – using jaust's `resample` ensures the write-enable
  signal is gated correctly; no manual interpolation is needed.
- **Hatari-like mixing** – `YmMixer` uses a physics-based conductance model
  (parallel resistor DAC approximation) consistent with the Hatari emulator,
  giving authentic timbre.
- **Real-time filter control** – LPF/HPF cutoff frequencies are read via
  `IntSupplier` on every sample, so the user can change filter settings while
  the audio line is running.

### Testing

- Tests cover file parsing (YM3, YM5/LZH-5), signal metadata, register
  round-trip fidelity, write-enable framing, and determinism across multiple
  `apply()` calls.

---

## Cons

### Signal-processing correctness

- **Box-filter downsampling loses high-frequency content unfaithfully** – the
  box filter from 2 MHz to 44.1 kHz aliases high-frequency content instead of
  attenuating it.  A sinc-based or Kaiser-windowed polyphase FIR would be more
  correct, although for this particular chip the box filter is an acceptable
  approximation given the limited bandwidth of the source.
- **Integer signal path limits dynamic range** – the 2 MHz signal chain passes
  through `INT` signals scaled to `[0, 32767]`, accumulating rounding errors at
  every step.  A `DOUBLE` or fixed-point path would preserve more precision
  before the final 16-bit quantisation.
- **`SequentialDoubleCache` is undocumented** – `buildFilterChain` wraps the
  mixed signal in a `SequentialDoubleCache` without explanation.  Its purpose
  (presumably to avoid re-evaluating the stateful chip per IIR coefficient
  iteration) is non-obvious to a new reader.

### Architecture

- **`StartDemo` is dead code in a public file** – the package-private
  `StartDemo` class and `StartPsg` sit in the same file as the public `Start`
  entry point.  They should be moved or removed.
- **`LpfOption.F1KHZ` constant name is wrong** – the constant is named `F1KHZ`
  (implying 1 kHz) but its `cutoffHz` field is `100` and its UI label is
  `"100 Hz"`.  The actual cutoff is 100 Hz; the constant name is incorrect.
- **No loop support in PSG player** – `runAudioLine` supports a `loop`
  parameter that `YmPlayer` passes as `true`, but `PsgPlayer` passes `false`,
  so PSG captures play once and stop.  A loop option in the UI would improve
  usability.
- **Tight coupling between filter chain and `AbstractPlayer`** –
  `buildFilterChain` creates `ButterworthLowPass`/`ButterworthHighPass`
  unconditionally even when the cutoff is 0 (bypass).  The bypass is handled
  inside the jaust filter (cutoff 0 → pass-through), but this is implicit.

### Testing gaps

- **No tests for the PSG path** – `PsgCaptureParser`, `PsgCaptureProcessor`,
  and `PsgYm2149Processor` have zero unit tests.
- **No tests for `YmMixer` or `LinearMixer`** – the mixing table and DAC ROM
  values are untested.
- **No WAV export round-trip test** – the WAV header writer and PCM encoding
  are exercised only in integration via the UI; a unit test writing a few
  samples and checking the byte output would catch regressions.
- **Integration / audio tests are absent** – there are no headless playback or
  end-to-end tests that verify the full pipeline produces sample values in
  the expected range.

### Dependency & build

- **JDK 25 requirement is bleeding-edge** – requiring an unreleased JDK build
  raises the barrier for contributors; a stable LTS release (21 or 17) would be
  preferable unless specific preview features are needed.
- **GitHub Packages token required for all dependencies** – both `jm2149_vhdl`
  and `jaust` are on GitHub Packages.  Contributors must configure a
  `GITHUB_TOKEN` in `~/.m2/settings.xml` before they can build.  Publishing to
  Maven Central or providing a local-install script would lower friction.
- **No version pinning for snapshots** – both `jm2149_vhdl` and `jaust` use
  `SNAPSHOT` versions, which can silently introduce breaking upstream changes.

---

## Suggested Improvements

### High priority

1. **Add unit tests for the PSG pipeline** – cover `PsgCaptureParser`,
   `PsgCaptureProcessor`, and `PsgYm2149Processor` analogously to the existing
   YM tests.

2. **Fix the `LpfOption.F1KHZ` constant name** – the constant is named
   `F1KHZ` but its cutoff is 100 Hz, not 1 kHz.  Rename to `F100HZ` to match
   the actual value and the existing UI label:
   ```java
   F100HZ("100 Hz", 100),
   ```

3. **Document `SequentialDoubleCache`** – add a comment explaining why the
   `DoubleSignal` wrapping is needed (avoids re-evaluating the stateful chip
   for each IIR tap) and what invariants it relies on.

4. **Clean up `Start.java`** – move `StartDemo` to a dedicated test or example
   source root, or delete it.  Keep `Start.main` → `YmPlayerApp.main` as the
   only production entry point.

### Medium priority

5. **Loop option for PSG player** – expose a loop checkbox in `PsgPlayerApp`
   and pass it through to `runAudioLine` so PSG captures can loop like YM
   files.

6. **WAV export unit test** – write a test that builds a short (e.g. 44 100
   sample) signal, exports it via `AbstractPlayer.exportWav`, and verifies the
   44-byte RIFF header and the first few PCM bytes.

7. **Consider pinning snapshot versions** – use a Maven `<version>` range or
   add a comment noting the minimum working snapshot version so breakage is
   detectable.

8. **Reduce JDK requirement** – audit the codebase for Java 25-specific
   features.  The code appears to use only mainstream Java 17–21 features
   (records, sealed types, pattern `var`, text blocks are absent); targeting
   Java 21 (LTS) would broaden compatibility.

### Low priority / Nice-to-have

9. **Consider a DOUBLE signal path for mixing** – run the YM2149 DAC indices
   through a `double[]` DACROM lookup and keep the signal as `DOUBLE` up to
   the final 16-bit quantisation to reduce rounding noise.

10. **Publish to Maven Central** – packaging `jaust` and `jm2149_vhdl` on
    Central would eliminate the GitHub Packages token requirement and ease
    adoption.

11. **Add a `README.md`** – a top-level README describing how to build, run,
    and test the project would significantly lower the on-boarding cost.

12. **Separate `LinearMixer` usage** – `LinearMixer` exists but is never used
    in the main pipeline; either document it as an alternative to `YmMixer`
    or remove it to avoid confusion.
