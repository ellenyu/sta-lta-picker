package edu.caltech.scedc.localpickew;

import java.util.Date;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.trinet.jasi.Channel;
import org.trinet.jasi.ChannelList;
import org.trinet.jasi.Phase;
import org.trinet.util.LeapSeconds;
import org.trinet.util.gazetteer.GeoidalUnits;
import org.trinet.util.gazetteer.LatLonZ;

/**
 * Converts a Jiggle Phase into pick JSON. Two formats:
 * <ul>
 * <li>{@code anss}: ANSS Pick (anss-data-formats/format-docs/Pick.md), lowercase keys, GeoJSON channel.</li>
 * <li>{@code soa}: SCSN SOA Pick (formats/format-docs/pick.md), capitalized keys, {@code Site}
 *     and the CI {@code Quality} array.</li>
 * </ul>
 * Absent values are omitted.
 */
public class PickJson {

    public static final String ANSS = "anss";
    public static final String SOA = "soa";

    static final String TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    private final String format;
    private final String agencyID;
    private final String author;
    private final ChannelList channelList; // optional, for station coordinates (anss only)

    public PickJson(String format, String agencyID, String author, ChannelList channelList) {
        if (!ANSS.equals(format) && !SOA.equals(format)) {
            throw new IllegalArgumentException("unknown format " + format);
        }
        this.format = format;
        this.agencyID = agencyID;
        this.author = author;
        this.channelList = channelList;
    }

    /** Blank location codes are written as "--", the SCSN convention. */
    static String locationCode(Channel ch) {
        String loc = ch.getLocation();
        return (loc == null || loc.trim().isEmpty()) ? "--" : loc.trim();
    }

    static String formatTime(double trueEpochSecs) {
        // Phase times are true (leap-second) epoch seconds; LeapSeconds converts to UTC.
        return LeapSeconds.trueToString(trueEpochSecs, TIME_FORMAT, "UTC");
    }

    /** Deterministic id, so reruns give the same ids. */
    static String pickId(Channel ch, String time, String phase) {
        return String.format("%s.%s.%s.%s.%s.%s",
                ch.getNet(), ch.getSta(), ch.getSeedchan(), locationCode(ch), time, phase);
    }

    /** PickEW writes "c." for up (compression) and "d." for down; ".." means undetermined. */
    static String polarity(Phase ph) {
        String fm = ph.description.fm;
        if (fm != null && fm.startsWith("c")) return "up";
        if (fm != null && fm.startsWith("d")) return "down";
        return null;
    }

    static String onset(Phase ph) {
        String ei = ph.description.ei;
        if ("i".equals(ei)) return "impulsive";
        if ("e".equals(ei)) return "emergent";
        return "questionable";
    }

    public JsonObject toJson(Phase ph) {
        return SOA.equals(format) ? toSoaJson(ph) : toAnssJson(ph);
    }

    /** SOA pick (formats/format-docs/pick.md), following the hypoPN examples in formats/examples. */
    JsonObject toSoaJson(Phase ph) {
        Channel ch = ph.getChannelObj();
        String time = formatTime(ph.getTime());
        String phase = ph.description.iphase;

        JsonObject pick = new JsonObject();
        pick.addProperty("Type", "Pick");
        pick.addProperty("ID", pickId(ch, time, phase));

        JsonObject site = new JsonObject();
        site.addProperty("Station", ch.getSta());
        site.addProperty("Channel", ch.getSeedchan());
        site.addProperty("Network", ch.getNet());
        // SOA examples write a blank location as "".
        String loc = locationCode(ch);
        site.addProperty("Location", "--".equals(loc) ? "" : loc);
        pick.add("Site", site);

        pick.addProperty("Time", time);

        JsonObject source = new JsonObject();
        source.addProperty("AgencyID", agencyID);
        source.addProperty("Author", author);
        pick.add("Source", source);

        pick.addProperty("Phase", phase);
        String pol = polarity(ph);
        if (pol != null) pick.addProperty("Polarity", pol);
        pick.addProperty("Onset", onset(ph));
        pick.addProperty("Picker", "sta-lta");

        // CI Quality array: PickEW quality (1 - weight/4) and the Hypoinverse weight (0-3).
        JsonArray quality = new JsonArray();
        quality.add(qualityEntry("PickEW", ph.getQuality()));
        quality.add(qualityEntry("hypoinverse", ph.description.getWeight()));
        pick.add("Quality", quality);
        return pick;
    }

    private static JsonObject qualityEntry(String standard, Number value) {
        JsonObject q = new JsonObject();
        q.addProperty("Standard", standard);
        q.addProperty("Value", value);
        return q;
    }

    /** ANSS pick (anss-data-formats/format-docs/Pick.md). */
    JsonObject toAnssJson(Phase ph) {
        Channel ch = ph.getChannelObj();
        String time = formatTime(ph.getTime());
        String phase = ph.description.iphase;

        JsonObject pick = new JsonObject();
        pick.addProperty("type", "Pick");
        pick.addProperty("id", pickId(ch, time, phase));
        pick.add("channel", channelJson(ch, ph.getDateTime()));
        pick.addProperty("time", time);

        JsonObject source = new JsonObject();
        source.addProperty("agencyID", agencyID);
        source.addProperty("author", author);
        pick.add("source", source);

        pick.addProperty("phase", phase);

        String pol = polarity(ph);
        if (pol != null) pick.addProperty("polarity", pol);
        pick.addProperty("onset", onset(ph));

        pick.addProperty("method", "PickEW");

        // Pick.md has no quality field; keep PickEW's weight/quality as an analytics extension.
        JsonObject pickew = new JsonObject();
        pickew.addProperty("weight", ph.description.getWeight());
        pickew.addProperty("quality", ph.getQuality());
        pickew.addProperty("deltaTime", ph.getDeltaTime());
        JsonObject extensions = new JsonObject();
        extensions.add("pickew", pickew);
        JsonObject analytics = new JsonObject();
        analytics.add("extensions", extensions);
        pick.add("analyticsInfo", analytics);

        return pick;
    }

    private JsonObject channelJson(Channel ch, Date when) {
        JsonObject channel = new JsonObject();
        channel.addProperty("type", "Feature");

        JsonArray coords = coordinates(ch, when);
        if (coords != null) {
            JsonObject geometry = new JsonObject();
            geometry.addProperty("type", "Point");
            geometry.add("coordinates", coords);
            channel.add("geometry", geometry);
        }

        JsonObject props = new JsonObject();
        props.addProperty("station", ch.getSta());
        props.addProperty("channel", ch.getSeedchan());
        props.addProperty("network", ch.getNet());
        props.addProperty("location", locationCode(ch));
        channel.add("properties", props);
        return channel;
    }

    /** [lon, lat, elevation_m] from the channel cache, or null if unknown. */
    private JsonArray coordinates(Channel ch, Date when) {
        if (channelList == null || channelList.isEmpty()) return null;
        Channel found = channelList.lookUp(ch, when);
        if (found == null || found == ch || !found.hasLatLonZ()) return null;
        LatLonZ llz = found.getLatLonZ();

        JsonArray coords = new JsonArray();
        coords.add(llz.getLon());
        coords.add(llz.getLat());
        // LatLonZ z is depth (positive down); channel elevations are stored as -elev/1000 km.
        // Units deserialized from the cache are new instances, so compare by value, not ==.
        if (!Double.isNaN(llz.getZ()) && llz.getZUnits() != null) {
            String units = llz.getZUnits().toString();
            if (units.equals(GeoidalUnits.KILOMETERS.toString())) coords.add(-llz.getZ() * 1000.0);
            else if (units.equals(GeoidalUnits.METERS.toString())) coords.add(-llz.getZ());
        }
        return coords;
    }
}
