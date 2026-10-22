# Pipeline corpus (SDD S.5, format v2)

English meetings for the meeting pipeline (Business → Engineering → Delivery). **Synthetic only**: no real person,
meeting or recording. Every piece of personal data is a planted marker (`SYNTH-PII-<nnnn>`), so scanners can prove it
never leaks, in its written form and in the forms Whisper writes when it is spoken.

Format v2 is Stint 3a.6's format v1 (`examples/stint-example-product-meeting/corpus`) plus optional fields; a v1
reader ignores them. `sandbox-contracts` reads both (`Corpus`), and `PipelineCorpusTest` checks the whole corpus (CA4).

## Layout
- `meetings/<project>-c<cycle>-<stage>.yaml`: one meeting.
- `manifest.json`: the SHA-256 of every script and, once generated, of every audio file (engine, version, voices).
- `pii-forms.json`: the scanner's list, generated from the corpus (`-Dcorpus.write=true` on `PipelineCorpusTest`).
- `audio/`: generated, git-ignored. `scripts/generate-audio.py corpus` regenerates it (Kokoro-82M on CPU) and records
  the hashes; `--verify` regenerates into a temporary directory and compares them with the manifest.

## Meeting format v2
```yaml
id: p1-c1-business             # meetingId; also <project>-c<cycle>-<stage>
project: p1                    # v2: projectId (the sandboxproject extension)
cycle: 1                       # v2: the cycle (sandboxcycle)
stage: business                # v2: business | technical (sandboxstage)
language: en
label: restricted              # a raw recording is restricted
speakers: [ { id: s1, voice: af_heart } ]
script:
  - { speaker: s1, text: "..." }
expected:                      # v2: the answer key of every LitJev decision
  meetingType: business-design # business-design | technical-refinement | out-of-scope
  channel: confluence          # business, in scope: email | confluence | meeting
  outcome: sdds                # technical, in scope: sdds | questions
  blockingQuestion: false      # technical: the noul "is there a blocking question?"
  priority: 0.8                # score in [0, 1], compared with a ±0.2 tolerance
  sddCount: 2                  # outcome sdds: how many SDDs
  sdds:                        # what each SDD must cover (coverage, not equality: Stint 3a.6 finding)
    - { id: p1-sdd-1, title: "...", covers: ["..."], failAttempts: 1 }   # failAttempts: fakes only
  questions: ["..."]           # outcome questions
  review: false                # true: the meeting is ambiguous on purpose, the decision is uncertain
pii:                           # v2: each planted marker and its forms
  - marker: SYNTH-PII-2011
    kind: name                 # email | phone | name | id-number
    written: SYNTH-PII-2011 Rivera                       # what the reference redactor recognizes
    spoken: [ "synth p i i two zero one one rivera" ]    # what Whisper may write (Stint 3a.6 finding)
```

## Contents (14 meetings)
| Group | Meetings |
|---|---|
| Business design, one per channel | `p1-c1-business` (Confluence), `p6-c1-business` (e-mail), `p3-c1-business` (new meeting) |
| Technical refinement | `p1-c1-technical` (2 SDDs), `p4-c1-technical` (3 SDDs), `p5-c1-technical` (1 SDD) |
| Cycle with questions | `p2`: cycle 1 business → technical with a blocking question; cycle 2 business with the answers → technical, 2 SDDs |
| Ambiguous (`review: true`) | `a1-c1-business` (design or technical?), `a2-c1-technical` (no decision) |
| Out of scope | `o1-c1-business` (team lunch), `o2-c1-technical` (another project) |

The skeleton (S.5 CA2) runs `p1` (two SDDs, one failing its first attempt) and `p2` (questions and restart).

## Rules
- Every marker in `pii` appears in the script, and every `SYNTH-PII-` in the script is listed. Markers are unique.
- Every spoken form names its marker's digits (as digits or words) or, for a phone, its number.
- Scripts are stored with LF endings (`.gitattributes`), so their hashes are the same on every checkout. Changing a
  script means updating its hash in the manifest and regenerating `pii-forms.json`.
