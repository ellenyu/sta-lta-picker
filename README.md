# localpickew — local STA/LTA picker for miniSEED

Runs the Earthworm-style STA/LTA picker (`org.trinet.jasi.picker.PickEW`) from [Jiggle](https://gitlab.com/aqms-swg/aqms-jiggle), part of the AQMS (ANSS Quake Monitoring System) code base, on local miniSEED files and writes **every** pick as a JSON array, in either the ANSS Pick format (`anss-data-formats/format-docs/Pick.md`, the default) or the SCSN SOA pick format (`formats/format-docs/pick.md`, `--format soa`). No database, no AWS. It is meant to feed an associator, so picks are unassociated.

It uses the same picker classes, `pickEW.props` and `pickEW.parms` as the `lambdapickew` POC, but works on continuous data. The lambda stopped after the first P/S per channel.

## Dependencies

* Java 8 or newer (`javac` and `jar` to build)
* `libs/jiggle_p8g-599881c.jar`: the same Jiggle build the lambda uses, built from [aqms-jiggle](https://gitlab.com/aqms-swg/aqms-jiggle)
* `libs/gson-2.10.1.jar`

## Build and run

```bash
./build.sh                                   # -> build/localpickew.jar
./localpickew.sh file1.mseed file2.mseed > picks.json
./localpickew.sh -o picks.json --channel-cache ../lambdapickew/pickew_JSON/app/src/main/resources/channelList.cache *.ms
./localpickew.sh --format soa -o picks_soa.json *.ms
```

Options:

| Option | Default | Meaning |
|---|---|---|
| `--props FILE` | `config/pickEW.props` | Picker properties. `picker.chanParmFile` is resolved relative to this file. |
| `-o, --out FILE` | stdout | Output file |
| `--format anss\|soa` | `anss` | Output format (see Output mapping) |
| `--channel-cache FILE` | none | Jiggle `channelList.cache`; adds `channel.geometry` `[lon, lat, elev_m]`. ANSS only; the SOA `Site` has no coordinates. |
| `--agency ID` / `--author NAME` | `CI` / `localpickew` | `source` / `Source` object |
| `--dead-time SECS` | 5 | Delay before the picker re-arms after a pick |
| `--chunk SECS` | 600 | Max seconds scanned per picker call |
| `--overlap SECS` | 30 | Overlap between chunks that had no pick |
| `--verbose` | off | Show Jiggle's own log output on stderr |

stdout carries only JSON. Progress, warnings and errors go to stderr. The exit status is 1 if any file or channel could not be read.

## How continuous picking works

`PickEW.pick()` is event-oriented: each call returns at most one P and one S. Each call also warms up (accumulates STA/LTA without picking) for `picker.restartLength` samples, which is 200, or 2 s at 100 sps. `ContinuousPicker` calls it repeatedly over each channel:

* After a pick, it re-arms at `pickTime + dead-time`.
* After a chunk with no pick, it advances by `chunk - overlap`, so an arrival on a chunk boundary is caught by the next scan.

Gaps of up to `picker.maxSampleGap` samples are interpolated by PickEW. Longer gaps restart its warm-up.

**Phase labels come from the channel, not the waveform.** No origin is used, so the P and S windows are the whole scan window. The `PickFlag` column in `pickEW.parms` then decides the label: Z channels (flag 1) give `P`, and horizontals (flag 2) give `S`. Treat `phase` as a hint for the associator.

**Limitations:**
* After each pick the picker is blind for `dead-time + warm-up` (about 7 s), so a second arrival inside that interval is missed.
* Picks are not filtered by quality. On a noisy station (for example CI.BOR) expect many low-weight noise triggers.
* Only Steim1/Steim2 miniSEED with records ≤ 8192 bytes is supported (`org.trinet.jasi.seed.SeedReader`). Other encodings are reported as errors.
* Channels missing from `pickEW.parms` are skipped with a warning.

## Output mapping

| ANSS (`--format anss`) | SOA (`--format soa`) | From |
|---|---|---|
| `type` | `Type` | `"Pick"` |
| `id` | `ID` | `NET.STA.CHA.LOC.<time>.<phase>`, deterministic (blank location is `--` in the id) |
| `channel.properties` | `Site` | Channel SNCL. A blank location is written as `--` (ANSS) or `""` (SOA, as in the `formats/examples` files) |
| `channel.geometry` | — | From `--channel-cache`; omitted if not given or not found |
| `time` | `Time` | Pick time, UTC, millisecond precision (Jiggle true epoch time converted with `LeapSeconds`) |
| `source` | `Source` | `--agency`, `--author` |
| `phase` | `Phase` | `P` or `S` (see above) |
| `polarity` | `Polarity` | PickEW first motion `c.` → `up`, `d.` → `down`; omitted when undetermined |
| `onset` | `Onset` | `i` → `impulsive`, `e` → `emergent`, otherwise `questionable` |
| `method` | `Picker` | `PickEW` / `sta-lta` |
| `analyticsInfo.extensions.pickew` | `Quality` | ANSS: `weight` (0–3), `quality` (1 − weight/4), `deltaTime` (s). SOA: `[{"Standard": "PickEW", "Value": quality}, {"Standard": "hypoinverse", "Value": weight}]` |

ANSS output validates against `anss-data-formats/python` (`anssformats.pick.Pick`). SOA output uses only keys defined in `formats/format-docs/pick.md` and includes all its required fields.

## Notes

* The serialized `channelList.cache` must have been written by a compatible Jiggle jar. If you swap in a newer jar, regenerate it with `cacheWrite.sh`.
* Jiggle's leap-second table has no DB here, so it falls back to its built-in table. Conversions into and out of Jiggle use that same table, so output UTC times are unaffected.
* The JVM may grow its heap well beyond what a day-long channel needs; `java -Xmx512m` is plenty for one day of 100 sps data.

## License

The code in this repository is released under the MIT License (see `LICENSE`). The bundled third-party jars in `libs/` are **not** covered by that license; each keeps its own upstream terms:

* `libs/jiggle_p8g-599881c.jar`: Jiggle, open-source software built from [aqms-jiggle](https://gitlab.com/aqms-swg/aqms-jiggle), part of the open-source AQMS code base. See that repository for its license terms.
* `libs/gson-2.10.1.jar`: [Gson](https://github.com/google/gson), Apache License 2.0.

`config/pickEW.props` and `config/pickEW.parms` are picker configuration copied from the AQMS lambdapickew proof of concept.

## References

* Jiggle, part of the AQMS code base: https://gitlab.com/aqms-swg/aqms-jiggle
* AQMS (ANSS Quake Monitoring System): Hartog, J. R., P. A. Friberg, V. C. Kress, P. Bodin, and R. Bhadha (2019). Open‐Source ANSS Quake Monitoring System Software. *Seismological Research Letters* 91 (2A): 677–686. https://doi.org/10.1785/0220190219
