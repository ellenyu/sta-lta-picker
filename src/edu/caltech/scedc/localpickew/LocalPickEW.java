package edu.caltech.scedc.localpickew;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;

import org.trinet.jasi.ChannelList;
import org.trinet.jasi.ChannelableList;
import org.trinet.jasi.Phase;
import org.trinet.jasi.Waveform;
import org.trinet.jasi.picker.PickEW;
import org.trinet.jasi.picker.PickerParmIF;
import org.trinet.jasi.seed.SeedReader;
import org.trinet.util.GenericPropertyList;

/**
 * Command-line STA/LTA picker: runs Jiggle's PickEW over local miniSEED files
 * and writes every pick as ANSS Pick JSON (anss-data-formats/format-docs/Pick.md).
 */
public class LocalPickEW {

    static final String USAGE =
        "Usage: localpickew [options] file.mseed [file.mseed ...]\n"
      + "  --props FILE          picker properties (default: <home>/config/pickEW.props)\n"
      + "  -o, --out FILE        write JSON here instead of stdout\n"
      + "  --format anss|soa     anss: ANSS Pick.md (default); soa: SCSN formats/format-docs/pick.md\n"
      + "  --channel-cache FILE  Jiggle channelList.cache, adds station coordinates (anss only)\n"
      + "  --agency ID           source.agencyID (default: CI)\n"
      + "  --author NAME         source.author (default: localpickew)\n"
      + "  --dead-time SECS      re-arm delay after a pick (default: 5)\n"
      + "  --chunk SECS          max seconds scanned per picker call (default: 600)\n"
      + "  --overlap SECS        overlap between empty chunks (default: 30)\n"
      + "  --verbose             show Jiggle's own log output on stderr\n";

    String propsFile = new File(System.getProperty("localpickew.home", "."), "config/pickEW.props").getPath();
    String outFile = null;
    String format = PickJson.ANSS;
    String channelCache = null;
    String agency = "CI";
    String author = "localpickew";
    double deadTime = 5.0;
    double chunk = 600.0;
    double overlap = 30.0;
    boolean verbose = false;
    List<String> inputs = new ArrayList<String>();

    public static void main(String[] args) {
        LocalPickEW app = new LocalPickEW();
        try {
            app.parseArgs(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.err.print(USAGE);
            System.exit(2);
        }
        System.exit(app.run());
    }

    void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-h") || a.equals("--help")) {
                System.out.print(USAGE);
                System.exit(0);
            } else if (a.equals("--props")) propsFile = value(args, ++i, a);
            else if (a.equals("-o") || a.equals("--out")) outFile = value(args, ++i, a);
            else if (a.equals("--format")) format = value(args, ++i, a).toLowerCase();
            else if (a.equals("--channel-cache")) channelCache = value(args, ++i, a);
            else if (a.equals("--agency")) agency = value(args, ++i, a);
            else if (a.equals("--author")) author = value(args, ++i, a);
            else if (a.equals("--dead-time")) deadTime = number(args, ++i, a);
            else if (a.equals("--chunk")) chunk = number(args, ++i, a);
            else if (a.equals("--overlap")) overlap = number(args, ++i, a);
            else if (a.equals("--verbose")) verbose = true;
            else if (a.startsWith("-")) throw new IllegalArgumentException("unknown option " + a);
            else inputs.add(a);
        }
        if (inputs.isEmpty()) throw new IllegalArgumentException("no miniSEED files given");
        if (!new File(propsFile).isFile()) throw new IllegalArgumentException("props file not found: " + propsFile);
        if (!format.equals(PickJson.ANSS) && !format.equals(PickJson.SOA)) {
            throw new IllegalArgumentException("--format must be anss or soa");
        }
        if (chunk <= overlap) throw new IllegalArgumentException("--chunk must be longer than --overlap");
    }

    private static String value(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " needs a value");
        return args[i];
    }

    private static double number(String[] args, int i, String opt) {
        try {
            return Double.parseDouble(value(args, i, opt));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(opt + " needs a number");
        }
    }

    int run() {
        // Jiggle logs with System.out; keep the real stdout for JSON only.
        PrintStream jsonOut = System.out;
        PrintStream log = System.err;
        PrintStream discard = new PrintStream(new OutputStream() {
            @Override public void write(int b) { }
        });
        System.setOut(verbose ? log : discard);

        // LeapSeconds' static initializer tries a DB connection and prints stack traces
        // before falling back to its built-in table; run it once with stderr muted.
        if (!verbose) {
            System.setErr(discard);
            try {
                Class.forName("org.trinet.util.LeapSeconds");
            } catch (ClassNotFoundException ex) {
                log.println("ERROR: " + ex);
                return 1;
            } finally {
                System.setErr(log);
            }
        }

        PickEW picker = new PickEW();
        picker.setProperties(new GenericPropertyList(propsFile, (String) null));
        if (!picker.hasChannelParms()) picker.loadChannelParms();
        if (!picker.hasChannelParms()) {
            log.println("ERROR: no channel parameters loaded (check picker.chanParmFile in " + propsFile + ")");
            return 1;
        }

        ChannelList channelList = null;
        if (channelCache != null && format.equals(PickJson.SOA)) {
            log.println("WARNING: --channel-cache is ignored for --format soa (Site has no coordinates)");
        } else if (channelCache != null) {
            channelList = ChannelList.readFromCache(channelCache);
            if (channelList == null || channelList.isEmpty()) {
                log.println("WARNING: could not read channel cache " + channelCache + "; picks will have no coordinates");
            }
        }

        ContinuousPicker continuous = new ContinuousPicker(picker, chunk, overlap, deadTime);
        List<Phase> allPicks = new ArrayList<Phase>();
        int failures = 0;

        for (String path : inputs) {
            ChannelableList wfList;
            try {
                wfList = SeedReader.getDataFromFile(path, 0);
            } catch (Exception ex) {
                log.println("ERROR: could not read " + path + ": " + ex);
                failures++;
                continue;
            }
            if (wfList == null || wfList.isEmpty()) {
                log.println("ERROR: no Steim1/Steim2 miniSEED data read from " + path);
                failures++;
                continue;
            }
            for (int i = 0; i < wfList.size(); i++) {
                Waveform wf = (Waveform) wfList.get(i);
                String sncl = wf.getChannelObj().toDelimitedSeedNameString(".");
                // SeedReader decodes only Steim1/Steim2; other encodings yield empty segments.
                if (!wf.hasTimeSeries() || wf.samplesInMemory() == 0) {
                    log.println("ERROR: " + sncl + " in " + path + " has no decodable samples (only Steim1/Steim2 are supported)");
                    failures++;
                    continue;
                }
                PickerParmIF parm = picker.getChannelParm(sncl);
                if (parm == null) {
                    log.println("WARNING: " + sncl + " is not in the picker parms file, skipped");
                    continue;
                }
                List<Phase> picks = continuous.pickAll(wf, parm);
                log.printf("%s: %d pick(s) from %s%n", sncl, picks.size(), path);
                allPicks.addAll(picks);
            }
        }

        Collections.sort(allPicks, new Comparator<Phase>() {
            @Override public int compare(Phase a, Phase b) {
                return Double.compare(a.getTime(), b.getTime());
            }
        });

        PickJson mapper = new PickJson(format, agency, author, channelList);
        JsonArray out = new JsonArray();
        for (Phase ph : allPicks) out.add(mapper.toJson(ph));

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try {
            Writer w = (outFile == null)
                    ? new OutputStreamWriter(jsonOut, StandardCharsets.UTF_8)
                    : new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
            gson.toJson(out, w);
            w.write("\n");
            w.flush();
            if (outFile != null) w.close();
        } catch (Exception ex) {
            log.println("ERROR: could not write output: " + ex);
            return 1;
        }
        log.printf("%d pick(s) written%s%n", allPicks.size(), outFile == null ? "" : " to " + outFile);
        return failures == 0 ? 0 : 1;
    }
}
