# COMP3011 Assignment 2 — Multithreaded Video Player

A JavaFX video player extended to support ordered, repeatable film-compositing effects from the command line and redesigned to process independent video frames concurrently on multicore CPUs.

 
> Student: Noah Tran
> Student ID: a2954835 
> Course: **COMP3011**  
> Project: **Assignment 2**  
> Original starter implementation: **Simon Ratcliffe, in collaboration with GPT-5.6 Terra**, as credited in the supplied code.  
> Development assistance: **[Describe any AI or other external assistance truthfully, according to course requirements.]**


## 1. Requirements and build


Run the commands **from the directory containing `pom.xml`**:

```bash
java -version
mvn -version
mvn validate
mvn clean compile
mvn package
mvn verify
```

To view the CLI help:

```bash
mvn compile javafx:run '-Djavafx.args=--help'
```

To play a local MP4 (replace the path with your own file):

```bash
mvn javafx:run "-Djavafx.args=-a -sfbn $HOME/Movies/COMP3011/trailer.mp4"
```


## 2. Command-line options

Original player options:

| Option | Behaviour |
| --- | --- |
| `-h`, `--help` | Show help and exit |
| `-a`, `--audio` | Enable sound at startup |
| `-x`, `--maximise` | Start maximised |
| `-1`, `--monitor-1` | Select display 1 |
| `-2`, `--monitor-2` | Select display 2 |

Film effects implemented by `FrameEffect`:

| Short | Long | Effect |
| --- | --- | --- |
| `-n` | `--number-frames` | Draw frame numbers |
| `-s` | `--scratch-frames` | Vertical film scratches |
| `-f` | `--flicker-frames` | Random dimming |
| `-w` | `--black-and-white` | Convert to black and white |
| `-y` | `--yellow-frames` | Warmer colour temperature |
| `-v` | `--vignette-frames` | Darken frame edges |
| `-d` | `--dust-frames` | Dust and hair marks |
| `-j` | `--jitter-frames` | Small positional displacements |
| `-m` | `--mottle-frames` | Emulsion mottling |
| `-b` | `--bleed-frames` | Light bleed |
| `-p` | `--pepper-frames` | Dark spots/blotches |

Short options can be stacked; long and short options can be mixed. The following request the same ordered effect sequence:

```text
-jfddn
-j -f -d -d -n
--jitter-frames --flicker-frames --dust-frames --dust-frames --number-frames
```

Repeated effects create distinct processor instances, preserving their place in the chain. `--help` / `-h` is handled before normal argument validation, so it can display help even when other options are invalid.

## 3. Playback controls

| Key | Action |
| --- | --- |
| `Space` | Pause / resume |
| `Left Arrow` | Seek back 5 seconds |
| `Right Arrow` | Seek forward 5 seconds |
| `Home` | Return to the beginning |
| `A` | Toggle audio |
| `Escape` | Stop / exit |

The GUI provides equivalent buttons and tooltips. These controls are wired in `VideoPlayerController` and drawn by `VideoPlayerView`.

## 4. Software architecture

### Main classes

| Class | Responsibility |
| --- | --- |
| `VideoPlayerApp` | JavaFX application and initial window configuration |
| `CommandLineController` | Parse input and validate CLI arguments |
| `FrameEffect` | Map effects to processor factories |
| `VideoPlayerController` | Connect GUI actions and application model |
| `VideoPlayerView` | Display frames, buttons, tooltips, and status |
| `VideoPlayerModel` | Playback state, media clock, audio scheduling, and ordered presentation |
| `VideoFramePipeline` | Background decoder, worker pool, buffering, and publication of completed frames |
| `FrameProcessor` subclasses | Perform the individual visual effects |
| `AudioPlayer` | Convert decoded PCM samples and write to a Java Sound output line |

### Concurrency design
- **One owner for FFmpeg:** Only the background decoder thread accesses its `FFmpegFrameGrabber` for reading and seeking at pipeline startup.
- **Independent frames:** The decoder clones a decoded `Frame` before submitting it to a worker. Different workers do not mutate the same frame buffer.
- **Independent effect state:** `ThreadLocal<WorkerState>` gives each worker its own list of `FrameProcessor` instances and any mutable scratch buffers used by those processors.
- **Deterministic repeated effects:** An `EnumMap<FrameEffect, Integer>` separately counts instances of each effect type when constructing a worker's chain. This preserves the Part 1 random-seed indexing and the required CLI effect order.
- **Ordered presentation:** Results can finish out of order, but a sequence-numbered `ConcurrentHashMap` allows `VideoPlayerModel` to accept only `nextSequence`.
- **Bounded video read-ahead:** A `Semaphore` limits the number of video frames in flight. The decoder waits for a permit; the JavaFX thread releases one when the corresponding frame is displayed.
- **Background failure reporting:** The pipeline publishes worker/decoder errors to a `volatile` field, which the model checks on the JavaFX thread.
- **Cancellation:** Seeking, stopping, or shutting down cancels the old playback session and clears its queued frames and audio. Old session results are not presented through the new model session.

### Thread ownership and critical regions

| Resource | Concurrency rule |
| --- | --- |
| `FFmpegFrameGrabber` | Access only on the decoder thread |
| Video `Frame` pixel buffers | Worker receives a separate cloned frame |
| Mutable effect processors | Per-worker `ThreadLocal` chain; no shared pixel scratch arrays |
| Completed frames | `ConcurrentHashMap` keyed by sequence number |
| Audio packet transfer | `ConcurrentLinkedQueue` carrying copied sample arrays |
| Playback state / UI | Owned by the JavaFX application thread |
| Worker lifecycle | `ExecutorService.shutdownNow()` on cancellation |
| Cancellation visibility | `AtomicBoolean` and `volatile` published state |

The design aims to avoid large global locks around effect processing, which would undermine parallel throughput. Audio line operations remain in the model's JavaFX-side scheduling path; audio continuity and responsiveness should be checked during manual testing.

## 5. Evidence of performance

The following numbers were **reported by TITAN in the supplied performance-test output**, rather than independently reproduced as part of this README preparation. 

| Effect workload | Entry playback FPS | Reported core use | Observations |
| --- | ---: | ---: | --- |
| `--number-frames` | 25.248 | 2.02 | Real-time |
| `-sfwyvdjmbp --number-frames` | 24.036 | 3.02 | Real-time; no missed frames reported |
| `-sfwyvdjmbppbmjdvywfs --number-frames` | 23.999 | 4.21 | Real-time; no missed frames reported |
| `-sfwyvdjmbppbmjdvywfssfwyvdjmbppbmjdvywfs --number-frames` | 21.804 | 7.36 | Fell below real-time; 14 missed frames reported |
| Single-threaded reference, heaviest workload | 6.574 | 1.55 | Fell below real-time |

For the heaviest workload, TITAN reported:

- **Relative performance:** 331.68% of the single-threaded reference (~3.32× playback throughput).
- **Startup speed:** 94% (TITAN's reported summary metric).
- **CPU utilisation:** 7.36 out of 8 logical cores (TITAN's reported utilisation metric).
- **Frame comparisons:** frames 958, 975, and 988 reported as matching the reference.

These results support improved parallel throughput but **do not prove every playback interaction or every possible effect sequence is free from bugs**. The heaviest workload did not maintain 24 FPS; this is recorded rather than concealed.

During local playback, the model prints `Frame headroom` messages for on-time/late-frame investigation. It also currently emits diagnostic `TIMING frame=...` output at 24-frame intervals. These are diagnostic outputs, not a replacement for measured playback FPS or regression tests.

## 6. Testing and verification

### Automated build checks

```bash
mvn validate
mvn clean compile
mvn package
mvn verify
```

## 7. Project structure

```text
pom.xml
README.md
src/
  main/
    java/
      comp3011/
        CommandLineController.java
        FrameEffect.java
        VideoPlayerApp.java
        VideoPlayerController.java
        VideoPlayerModel.java
        VideoFramePipeline.java
        VideoPlayerView.java
        FrameProcessor.java
        Frame*.java                 # Individual effects
        AudioPlayer.java
        InfoFrame.java
        InfoVideo.java
        VideoPlayerAppLauncher.java
```

The source archive intentionally does **not** contain `target/`, `.git/`, media files, or generated macOS `__MACOSX` metadata. If you add tests, include `src/test/java` in the submission ZIP.

## 8. Known limitations and further work
- Actual startup, seeking, pause, audio, monitor selection, and tooltip behaviour must be regression-tested on the required environment; the TITAN performance result alone does not exercise every interaction.
- The heaviest tested combination falls below 24 FPS, although it outperformed the reference by a substantial margin.
- Automated test sources are not present in the currently reviewed ZIP. Prioritise a small, meaningful test suite before final grading.
