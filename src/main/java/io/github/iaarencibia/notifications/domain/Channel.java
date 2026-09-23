package io.github.iaarencibia.notifications.domain;

/** The mechanism a notification is delivered through. */
public enum Channel {

    /** Structured JSON on stdout. Always available, and the one channel that cannot fail. */
    LOG,

    /** HTTP POST to the URL supplied as the recipient. */
    SERVICE,

    /** Accepted by the schema but not implemented. */
    EMAIL
}
