package edu.caltech.scedc.localpickew;

import java.util.ArrayList;
import java.util.List;

import org.trinet.jasi.Phase;
import org.trinet.jasi.PhaseList;
import org.trinet.jasi.Waveform;
import org.trinet.jasi.picker.PhasePickerIF;
import org.trinet.jasi.picker.PickerParmIF;
import org.trinet.util.TimeSpan;

/**
 * Runs PickEW over an arbitrarily long waveform and returns every trigger.
 *
 * PickEW.pick() is event-oriented: each call keeps at most one P and one S and
 * returns, and each call warms up on its first picker.restartLength samples
 * without picking. To pick a continuous trace we call it repeatedly:
 * after a pick, re-arm at pickTime + deadTime; after an empty chunk, advance
 * by one chunk minus the overlap so an arrival at a chunk boundary is caught
 * by the next scan once its warm-up is done.
 *
 * Because pSpan == sSpan == scan span, P vs S is decided only by the channel's
 * PickFlag in the parms file (Z -> P, horizontals -> S).
 */
public class ContinuousPicker {

    private final PhasePickerIF picker;
    private final double chunkSecs;
    private final double overlapSecs;
    private final double deadTimeSecs;

    public ContinuousPicker(PhasePickerIF picker, double chunkSecs, double overlapSecs, double deadTimeSecs) {
        if (chunkSecs <= overlapSecs) {
            throw new IllegalArgumentException("chunk (" + chunkSecs + " s) must be longer than overlap (" + overlapSecs + " s)");
        }
        this.picker = picker;
        this.chunkSecs = chunkSecs;
        this.overlapSecs = overlapSecs;
        this.deadTimeSecs = deadTimeSecs;
    }

    /** Returns all picks on one channel's waveform, in time order. */
    public List<Phase> pickAll(Waveform wf, PickerParmIF parm) {
        List<Phase> picks = new ArrayList<Phase>();
        double wfEnd = wf.getEpochEnd();
        double cursor = wf.getEpochStart();

        while (cursor < wfEnd) {
            double scanEnd = Math.min(cursor + chunkSecs, wfEnd);
            TimeSpan span = new TimeSpan(cursor, scanEnd);
            PhaseList phases = picker.pick(wf, PhasePickerIF.DEFAULT, parm, span, span, span);

            double lastPickTime = -Double.MAX_VALUE;
            if (phases != null) {
                for (int i = 0; i < phases.size(); i++) {
                    Phase ph = (Phase) phases.get(i);
                    lastPickTime = Math.max(lastPickTime, ph.getTime());
                    if (!isDuplicate(picks, ph)) picks.add(ph);
                }
            }

            if (lastPickTime > -Double.MAX_VALUE) {
                // Re-arm after the pick; always move forward so the loop terminates.
                cursor = Math.max(lastPickTime + deadTimeSecs, cursor + 1.0);
            } else if (scanEnd >= wfEnd) {
                break;
            } else {
                cursor = scanEnd - overlapSecs;
            }
        }
        return picks;
    }

    private boolean isDuplicate(List<Phase> picks, Phase ph) {
        for (int i = picks.size() - 1; i >= 0; i--) {
            Phase prev = picks.get(i);
            if (prev.description.iphase.equals(ph.description.iphase)
                    && Math.abs(prev.getTime() - ph.getTime()) < deadTimeSecs) {
                return true;
            }
        }
        return false;
    }
}
