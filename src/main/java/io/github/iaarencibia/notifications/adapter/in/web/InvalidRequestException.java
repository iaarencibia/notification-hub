package io.github.iaarencibia.notifications.adapter.in.web;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The headers a caller sent that the service will not accept, carried out with the name of each
 * header attached so the response can say which ones and why.
 *
 * <p>Holds every offending header, not only the first: the body path already reports every field in
 * one round trip, and holding the two paths to different standards would be worse than either.
 */
public class InvalidRequestException extends RuntimeException {

    private final Map<String, String> reasonsByHeader;

    /**
     * @param reasonsByHeader what is wrong with each refused header, keyed by the header's name
     *     exactly as it travels on the wire, and phrased for whoever has to fix the caller
     */
    public InvalidRequestException(Map<String, String> reasonsByHeader) {
        // Stored unprefixed: the response already keys each reason under the header name, so
        // repeating it there would stutter. Only this message, seen only by a log, joins name
        // to reason.
        super(
                reasonsByHeader.entrySet().stream()
                        .map(refusal -> refusal.getKey() + " " + refusal.getValue())
                        .collect(Collectors.joining("; ")));
        // Sorted, so two bad headers produce the same response whichever order they were
        // checked in.
        this.reasonsByHeader = Collections.unmodifiableMap(new TreeMap<>(reasonsByHeader));
    }

    Map<String, String> reasonsByHeader() {
        return reasonsByHeader;
    }
}
