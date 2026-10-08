package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.Map;

/** Upcoming public holidays to time posts around cultural moments — the Nager.Date API. */
public class HolidayMomentsTool extends PublicApiTool {

    public HolidayMomentsTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "moment_calendar";
    }

    @Override
    public String getDescription() {
        return "Lists the next public holidays in a country so content can be timed to cultural moments. "
                + "Args: {\"country\": \"US\"} (ISO 3166-1 alpha-2, optional).";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String country = arg(args, "country").toUpperCase();
        if (!country.matches("[A-Z]{2}")) country = "US";
        Fetched fetched = fetch("https://date.nager.at/api/v3/NextPublicHolidays/" + country, "nager-holidays");

        StringBuilder out = new StringBuilder("Upcoming moments in " + country + ":\n");
        int n = 0;
        for (JsonNode day : fetched.json()) {
            out.append("- ").append(text(day, "date")).append(": ").append(text(day, "name"));
            if (!text(day, "localName").equals(text(day, "name"))) {
                out.append(" (").append(text(day, "localName")).append(')');
            }
            out.append('\n');
            if (++n >= 6) break;
        }
        if (n == 0) out.append("- (none returned)\n");
        return out.append(fetched.sourceLine()).toString();
    }
}
