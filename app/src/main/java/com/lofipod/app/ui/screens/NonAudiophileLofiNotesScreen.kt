@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Plain-language companion to [AudiophileNotesScreen]. Same content territory
 * (what the audio chain does, what every control on the EQ screen means), but
 * written for a reader who doesn't already know the vocabulary. Definitions
 * first; technical claims second; recipes ("I want to..." -> setting) last.
 *
 * Hand-written, original prose. Kept in step with the audiophile-notes page —
 * if a spec changes there, the corresponding plain-language section here
 * needs an update too.
 *
 * SelectionContainer wraps the body so anyone can copy a definition out into
 * a chat or note.
 */
@Composable
fun NonAudiophileLofiNotesScreen(
    onBack: () -> Unit,
    onOpenAudiophileNotes: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Non-audiophile Lofi notes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CrossLink(label = "Notes for audiophiles", onClick = onOpenAudiophileNotes)

            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    Section("Who this page is for", AUDIENCE)
                    SectionDivider()
                    Section("Words to know", WORDS)
                    SectionDivider()
                    Section("What LofiPod does to your audio", BIG_PICTURE)
                    SectionDivider()
                    Section("Master gain (the volume booster)", MASTER_GAIN)
                    SectionDivider()
                    Section("Equalizer (EQ) — the six tone knobs", EQ_INTRO)
                    Section("The frequency map", FREQUENCY_MAP)
                    Section("What each band actually controls", BAND_BY_BAND)
                    Section("Q (the width of each knob's reach)", Q_FACTOR)
                    SectionDivider()
                    Section("Phase modes (Minimum vs Linear)", PHASE_MODES)
                    SectionDivider()
                    Section("DC blocker", DC_BLOCKER)
                    SectionDivider()
                    Section("Skip silence", SKIP_SILENCE)
                    SectionDivider()
                    Section("The limiter (the safety net)", LIMITER)
                    SectionDivider()
                    Section("Pass-through and \"Hold to A/B\"", PASSTHROUGH)
                    SectionDivider()
                    Section("Recipes — \"I want to...\"", RECIPES)
                    SectionDivider()
                    Section("How to tell if it's actually working", VERIFY)
                    SectionDivider()
                    Section("If you want to go deeper", FURTHER)
                }
            }

            Spacer(Modifier.height(16.dp))
            CrossLink(label = "Notes for audiophiles", onClick = onOpenAudiophileNotes)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Right-justified text-link row used at top + bottom of the page. */
@Composable
private fun CrossLink(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) { Text(label) }
    }
}

/**
 * Two-tier section: titleMedium heading with primary color, bodyMedium body.
 * Slightly larger than the audiophile-notes screen on purpose — this page is
 * meant to be approachable, not dense, so the headings get more visual
 * weight and the body uses the larger reading size.
 */
@Composable
private fun Section(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    Text(reflowProseLofi(body), style = MaterialTheme.typography.bodyMedium)
}

/** Subtle divider between top-level sections — quiet visual breath. */
@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}

/**
 * Same idea as AudiophileNotesScreen.reflowProse: paragraphs (separated by
 * blank lines) get reflowed to device width; lines that look like a code /
 * diagram block (indented two spaces or starting with a glyph used in the
 * diagrams below) keep their original alignment.
 */
private fun reflowProseLofi(s: String): String {
    val trimmed = s.trim()
    return trimmed.split("\n\n").joinToString("\n\n") { paragraph ->
        val lines = paragraph.lines()
        val isDiagram = lines.any { line ->
            line.startsWith("  ") ||
                line.trimStart().startsWith("|") ||
                line.trimStart().startsWith("-")
        }
        if (isDiagram) paragraph
        else lines.joinToString(" ") { it.trim() }
    }
}

// ============================================================================
// CONTENT
// ============================================================================
// Original prose. No external sources cited because every concept covered is
// generic audio knowledge that's been part of the field for decades. If a
// future passage borrows specific framing from a paper or article, add a
// citation inline in that passage rather than at the file level.
// ============================================================================

private const val AUDIENCE = """
This page is for the curious listener — someone who's noticed the EQ sliders
in LofiPod and wants to know what they actually do without having to learn
what a "biquad" is first. Plain language, definitions where they help,
recipes at the bottom for "I want to make voices clearer."

For the technical version of the same content (with specific filter
topologies, dB ripple budgets, FIR tap counts, and the math behind every
choice) tap the "Notes for audiophiles" link above or below. Both pages are
describing the same audio chain — they just talk about it differently.
"""

private const val WORDS = """
A short glossary. Each entry is one quick technical line, then a friendlier
expansion.

Lofi (noun)
Shorthand for low-fidelity. Stands against high-fidelity, which is the goal
of representing or reproducing source material with high faithfulness.
LofiPod is an ironic name: the app aims toward audiophile-grade podcast
playback, knowing that many recorded podcasts and sermons carry imperfections
from the recording end and that more imperfections can creep in on the
playback end. The features in this app exist so a listener can either tease
out a more faithful representation of the recorded content, or at least
land on a more pleasant and finely-tuned listening experience.

Audiophile (noun)
A lover of audio. Practically: someone who cares about how playback sounds
beyond "loud enough to hear" — they pay attention to clarity, accuracy,
imaging (the sense of where sounds are placed in space), dynamics (the
contrast between quiet and loud), and the absence of artifacts (clicks,
hiss, distortion).

DSP — Digital Signal Processing
The math LofiPod runs on the audio between the file on disk and your
speakers / headphones. "Signal processing" is a broad engineering term;
"digital" means the math runs on the discrete numbers that make up a digital
audio file (rather than on a continuous voltage in an analog circuit).
Concretely: every sample of audio gets read, transformed, and written before
your phone's hardware turns it into sound.

Hertz (Hz) and kilohertz (kHz)
The unit of frequency — how many times per second a wave repeats. Bass is
low frequency (tens of Hz); treble is high frequency (thousands of Hz, often
written as kHz where 1 kHz = 1000 Hz). Healthy young human hearing covers
roughly 20 Hz to 20,000 Hz (20 kHz). Most adults lose the top end with age
and noise exposure — by 40 the ceiling is often closer to 14 kHz.

Decibel (dB)
A logarithmic unit for relative loudness. The key intuitions:
  +6 dB is roughly twice the linear amplitude of the signal.
  +10 dB is roughly twice as loud as the brain hears it.
  -3 dB is half the power, perceived as a small but clear drop.
  0 dB on the EQ sliders means "no change" (not silence).
The logarithmic scale matches how the ear actually perceives loudness — a
straight linear knob would feel useless at low settings and uselessly
extreme at high ones.

dBFS — decibels relative to full scale
Same dB scale, but measured against the loudest a digital signal CAN be.
0 dBFS is the maximum; everything else is negative. -6 dBFS is half the
linear amplitude of full scale; -60 dBFS is so quiet it's essentially the
noise floor of a CD-quality file.

PCM — Pulse Code Modulation
The standard way digital audio is stored: a stream of numeric samples, one
per "moment in time," each one representing the air-pressure level the
speaker should produce at that instant. The numbers in LofiPod's chain are
16-bit (each sample is one of 65,536 possible levels). When you read "PCM
int16 in" on the audiophile notes page, that's what it means: a stream of
16-bit numbers.

Sample rate
How many times per second the audio was digitized when it was recorded.
44,100 samples per second (44.1 kHz, the CD-quality rate) is standard;
48 kHz is also common. Higher sample rates can capture higher frequencies,
but anything past ~48 kHz is past human hearing and only matters for
mathematical reasons during processing.

EQ — equalizer
A tool for boosting or cutting specific frequency ranges. Originally so
named because it was used to "equalize" frequency-response losses introduced
by recording or transmission. Today it's also used creatively — to make a
podcast sound warmer, brighter, less boomy, more intimate.

Latency
The delay between sound being decoded from the file and reaching your ears.
LofiPod's EQ adds a few milliseconds; you don't notice it at all for
podcasts and music.
"""

private const val BIG_PICTURE = """
By default, LofiPod plays your audio as-is. Every EQ slider sits at 0 dB
(no change), the master gain is at 0 dB, and the chain effectively gets out
of the way — the audio coming out of the file is what reaches your ears.

When you start moving sliders, the audio gets routed through a chain of
processors before it leaves the app. The chain is short and predictable:

  audio file
    -> small DC fix (optional, off by default)
    -> equalizer (six tone knobs)
    -> master gain (volume booster)
    -> a safety net that catches peaks before they distort
    -> your speakers / headphones

Two important things about this chain:

First, when nothing is doing anything (all EQ sliders at 0, gain at 0, DC
blocker off), LofiPod takes a fast lane that bypasses the chain entirely.
The audio is mathematically identical to the file. So the cost of having
all these features sitting there available is zero when you're not using
them.

Second, none of these processors are doing anything sneaky. There are no
"sound enhancers" guessing at what you might want. Every change to the
audio is something you either turned on or moved a slider for. If you
hear a difference between LofiPod and another player, it's because of a
specific control you can identify and adjust.
"""

private const val MASTER_GAIN = """
Quick technical: a volume multiplier applied after the EQ. Range 0 to
+12 dB.

In friendly terms: this is a volume boost that lives inside LofiPod, on
top of your phone's hardware volume. Useful when:

  - The podcast was recorded too quietly. Some old sermon recordings or
    home-studio podcasts come out 10+ dB below mainstream productions.
    Master gain can bring them up to par without you having to crank the
    phone volume past comfortable for OTHER content.
  - You're listening on speakers in a noisy room. A few extra dB of master
    gain can help speech cut through ambient noise.

What "boost" means here, exactly: each sample of audio gets multiplied by a
number bigger than 1. A +6 dB boost roughly doubles the linear amplitude;
+12 dB is roughly four times. The limiter (described below) keeps the
amplified peaks from going over the safe ceiling, so you can crank the
master gain without the audio clipping into nasty distortion. It does NOT
make the audio louder than your phone's hardware volume can produce — it
shifts how much of the digital range is being used. Past a certain point
the limiter starts squashing peaks heavily and the audio loses dynamic
contrast. For voice content, +3 to +6 dB is usually plenty; +12 dB is the
ceiling for "this recording is just way too quiet" emergencies.

Master volume vs master gain — they're different. Master volume is the
hardware volume on your phone (the side buttons). Master gain is digital
amplification inside the app. Use master gain when the source is too quiet;
use master volume to control how loud playback is overall.
"""

private const val EQ_INTRO = """
The equalizer (EQ) is a row of six tone knobs, each tied to a specific
frequency range. Pulling a knob up boosts that range; pulling it down
attenuates it. Leave a knob at 0 dB and that range passes through
untouched.

Why six knobs and not three (bass / mid / treble)? Three is enough for
casual stereo controls but too coarse for podcast work. A boomy male
voice and a boomy room have problems in different sub-ranges of "bass"
that three knobs can't separate. Six is fine-grained enough to address
specific issues (sibilance, mud, thinness) without becoming a 31-band
graphic EQ that nobody uses confidently.
"""

private const val FREQUENCY_MAP = """
A rough sketch of where each EQ band sits in the spectrum, with the
real-world things that live there:

   sub-bass  bass    low-mid    mid       high-mid    air
   |         |       |          |         |           |
   31 Hz   62 Hz   125 Hz     500 Hz    2 kHz       8 kHz
   |         |       |          |         |           |
   |  felt   bass    warmth    body      presence   sparkle
   |  more   guitars boomy     of voice  consonant   "S" and
   |  than   kick    rooms     and       clarity     cymbal
   |  heard  drums   if too    music                 sheen
   |                 much

A few more reference points to anchor your intuition:

  - Adult male speaking voice — fundamental frequencies live around
    80 to 180 Hz. The character of the voice (its harmonics) extends
    up through 2 to 3 kHz.
  - Adult female speaking voice — fundamentals around 165 to 255 Hz.
    Harmonics extend up through 4 to 5 kHz.
  - Sibilance — the hissy "S" / "SH" / "F" sounds that can become
    piercing on bright headphones — sits around 5 to 8 kHz.
  - Phone-call thin sound — phones typically pass roughly 300 Hz to
    3,400 Hz only. That gap (no bass below 300 Hz, no air above
    3,400 Hz) is what makes a phone call sound flat compared to a
    podcast played on the same phone.
"""

private const val BAND_BY_BAND = """
What each band tends to do when you push it up or down. These are
generalizations — the real answer for a given recording is "try it and
listen."

31 Hz — sub-bass
This is the felt-rather-than-heard register. Earthquake rumble, the deep
weight under a kick drum, organ pedal notes. For most podcasts there's
nothing meaningful here; cutting (negative dB) can clean up rumble from
a recording made in a non-treated room. Boosting tends to be unproductive
through phone speakers (which can't reproduce these frequencies) and
risky through headphones (eats power and headroom).

62 Hz — bass
The fundamentals of male voices and the bass guitar register. A small
boost (+1 to +3 dB) can warm up a thin voice; a small cut can clean up
muddiness in voices recorded with bass-heavy microphones close to the
mouth.

125 Hz — low-mid (a.k.a. "warmth" or "boom")
This is where male voices feel chesty and where badly-recorded rooms
boom. If a podcast sounds boomy or boxy — like the speaker is in a
small kitchen — try cutting 125 Hz by 2 to 4 dB. If a voice sounds
overly thin or "telephone-like," try a small boost.

500 Hz — midrange (the body of speech and music)
The fundamental tone of the human voice's articulation lives around
here. Cutting too aggressively will make voices sound hollow or distant
("scooped out"). A small cut can reduce nasal honk; a small boost can
make speech feel more present and full-bodied.

2 kHz — high-mid (presence and consonant clarity)
This is where consonants become intelligible. A small boost (+1 to
+3 dB) on a muffled recording can dramatically improve clarity — the
difference between "I can't quite catch the words" and "I can follow
along easily." Push too far and the recording becomes harsh / fatiguing
on long listens.

8 kHz — high (air and sibilance)
The "sparkle" register. A small boost adds a sense of air and openness;
too much makes "S" sounds piercing (look up "sibilance" if you ever want
to feel sympathy for podcast editors). Cutting here is the standard fix
for a recording that's too hissy or piercing on bright headphones.
"""

private const val Q_FACTOR = """
The Q value (sometimes shown next to a band) controls how WIDE each
knob's reach is. Low Q means the boost or cut covers a wide range of
neighboring frequencies (gentle, broad shaping); high Q means the change
is narrow and surgical, focused tightly on the band's center.

For everyday podcast tweaking, the default Q values built into LofiPod
are fine — they're tuned to be musical rather than surgical. You only
need to think about Q if you're trying to remove a specific narrow
problem (a microphone resonance at 250 Hz, a hum at 60 Hz). Most
listeners never need to touch it.
"""

private const val PHASE_MODES = """
LofiPod's EQ can run in either of two modes. Most listeners should leave
this on Minimum (the default).

Minimum phase — the default
The "normal" way EQ has worked for decades, in both analog and digital
gear. Slightly different parts of the audio reach your ears at slightly
different times (we're talking sub-millisecond differences), but the ear
can't perceive that. Adds about 6 milliseconds of total latency.
Inaudible.

Linear phase — opt-in
A more mathematically pure way to do the same EQ shape. Every frequency
gets delayed by exactly the same amount (around 46 milliseconds total),
which preserves the original timing relationships in the audio. The
practical benefit is subtle — you might notice it on percussive content
with sharp transients (snares, claps) where a linear-phase EQ keeps the
attack feeling cleaner. For voice and most podcasts, the difference is
inaudible. Costs a few times more CPU and adds the longer latency, both
of which are still totally fine on modern phones.

Why the option exists at all: some audiophiles specifically want to
verify the timing structure of a recording without an EQ smearing it.
Linear phase is the right tool for that. If you're not sure whether you
need it, you don't.
"""

private const val DC_BLOCKER = """
Very technical (kept short on purpose): a single-pole high-pass filter
with a corner around 5 Hz that strips out any frequencies below human
hearing.

Friendlier version: some audio sources carry a tiny constant offset
("DC" — direct current — borrowed from electrical engineering language).
You can't hear it, but it silently steals headroom from the limiter
downstream and can cause subtle weirdness on very quiet passages. The
DC blocker removes that offset.

When to turn it on: if you're listening to low-bitrate MP3 sermons,
old radio rips, or recordings that just sound "off" in some way you
can't quite name on quiet passages — flip this on and see if it helps.
For modern, well-mastered podcasts there's nothing to fix and you can
leave it off.
"""

private const val SKIP_SILENCE = """
What it does: detects gaps in the audio (pauses between sentences, dead
air between intro music and speech) and shortens them so you spend less
real time waiting through silence.

Levels: each level is more aggressive than the last. Off (level 0) leaves
the audio untouched. Higher levels detect and shorten increasingly subtle
silences.

Trade-off: more aggressive levels save more time but start affecting the
natural rhythm of speech — a thoughtful pause for emphasis can get
clipped, making the speaker sound rushed. Try level 1 or 2 first; only
go higher if you're listening to content where the silence-to-speech
ratio is genuinely high (some sermon recordings).

Why it's a separate stage: skip-silence sits outside the EQ chain. It
just changes how much audio gets played per real-time second; it doesn't
change the audio that DOES play.
"""

private const val LIMITER = """
Quick technical: a look-ahead brick-wall limiter on the chain output.
Threshold at -1 dBFS, soft knee, linked stereo, ~5 ms look-ahead, runs
at 2x sample rate inside an oversampling envelope.

Plain language: a safety net that prevents loud peaks in the audio from
going past the maximum that 16-bit digital audio can represent. If they
DID go past, you'd hear nasty clicky distortion called "clipping." The
limiter catches them just before they would clip and turns them down
gently enough that you don't hear it doing anything.

Why "look-ahead": the limiter reads a small slice of the future of the
audio (5 milliseconds) so it knows a peak is coming and can start
softening it before it arrives, instead of reacting after the fact.
That makes the gain reduction inaudibly smooth instead of clicky.

Why "linked stereo": when a peak appears on only one side of a stereo
recording, both sides get turned down by the same amount. Without this,
a transient on the left ear would briefly push only that channel down,
shifting the sense of where things are placed in space.

You don't have a slider for the limiter — it's always running, always
the same. It only kicks in when the master gain or an EQ boost pushes
audio above the safe ceiling.
"""

private const val PASSTHROUGH = """
Pass-through means LofiPod's audio chain is OUT of the picture and the
audio coming out of the decoder is going straight to your speakers,
unchanged. This happens automatically when:

  - The Audio enhancement master switch is off, OR
  - All EQ sliders are at 0 dB AND the master gain is at 0 dB AND the
    DC blocker is off

In pass-through, the audio is mathematically identical to what's in the
file (or, for streamed content, what came down the network). Useful as
a reference baseline.

"Hold to A/B" (on the Audio screen) is a button that forces pass-through
for as long as you hold it down. The instant you release, your full
chain is back. Useful for "is this EQ tweak actually helping?" — hold
to hear the original, release to hear your tweaked version, A/B as many
times as you want. Your settings are never changed by this button; it's
purely momentary.
"""

private const val RECIPES = """
Starting points for common goals. Try the suggested moves, then adjust
to taste while listening.

Goal: make a muffled voice clearer.
  - Boost 2 kHz by +2 to +4 dB. This is where consonants live — adding
    here makes "T" / "K" / "S" pop out more.
  - Optionally boost 8 kHz by +1 to +2 dB for a touch of air.
  - If the result is harsh, back off both.

Goal: tame a boomy / boxy / "kitchen-sounding" room.
  - Cut 125 Hz by -2 to -4 dB. This is the most common podcast problem.
  - If the boom is more in the 60-Hz register, cut 62 Hz instead.

Goal: warm up a thin / nasal / phone-call voice.
  - Boost 62 Hz or 125 Hz by +2 to +3 dB.
  - Optionally cut 2 kHz slightly to soften nasal honk in the upper-mid.

Goal: reduce piercing "S" sounds (sibilance).
  - Cut 8 kHz by -2 to -4 dB.
  - The trade-off: you also lose some air. If it sounds dull, dial back
    the cut.

Goal: listen quietly without losing detail (e.g., late-night listening).
  - Boost 125 Hz by +1 dB and 2 kHz by +2 dB. The ear's frequency
    sensitivity changes at low volume — these gentle boosts compensate
    for what gets harder to hear when you turn the volume down. Old
    HiFi gear used to call this "loudness" compensation.

Goal: just make a too-quiet podcast louder.
  - Master gain +3 to +6 dB. Don't touch the EQ first — quiet is a
    volume problem, not a tone problem.

Goal: get back to neutral.
  - Open the EQ screen. The "FLAT" preset zeroes every band. Master
    gain to 0. DC blocker off. You're now in pass-through.
"""

private const val VERIFY = """
The fastest way to confirm something is doing what you think it is:
"Hold to A/B" (the button on the Audio screen). Hold to hear the
original audio with the chain bypassed; release to hear your settings.
The difference between the two IS the contribution of everything you've
set up.

For more detail than your ears can give: the Audio diagnostics screen
(Settings -> Audio diagnostics) shows live numbers for what every stage
of the chain is currently doing — peak levels at the input and output
of the chain, how hard the limiter is working, whether the chain is in
pass-through, and so on. You don't need to read it to enjoy LofiPod —
but it's there if you ever want to double-check that something isn't
working the way you expect.
"""

private const val FURTHER = """
If you want the engineering version of any topic on this page —
specific filter coefficients, FIR tap counts, the math behind the
limiter's soft knee, total chain latency in samples, the linear-phase
kernel's group delay — tap "Notes for audiophiles" at the top or
bottom of this page. Same chain, more depth.

If you want to see the chain in action while you're listening, the
Audio diagnostics screen (Settings -> Audio diagnostics) is the live
view: peak meters, load factor, event log, recent transitions.

And if you ever want to share a snapshot of what your settings sound
like to a friend (or to a future version of yourself who wants to know
what changed), the Audio diagnostics screen has a "Copy to clipboard"
button that dumps every relevant number as plain text.
"""
