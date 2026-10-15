package dev.veil.policy;

/** What to do about a finding, ordered from most permissive to strictest. The strictest matching action wins. */
public enum Action {
    /** Send as is. */
    ALLOW,
    /** Send as is, but flag the request in the audit log (and warn the user in the browser extension). */
    WARN,
    /** Replace the value with a placeholder before sending and put it back in the answer. */
    REDACT,
    /** Keep the data on premises: send the request to the organisation's local model instead. */
    ROUTE_LOCAL,
    /** Refuse the request. */
    BLOCK;

    public Action max(Action other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
