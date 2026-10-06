package me.kavin.piped.utils.resp;

/**
 * Body of an unexpected error. The frontend shows {@code message}, while other clients read {@code error},
 * so both carry the same text. Never put a stack trace in here.
 */
public record ErrorMessageResponse(String error, String message) {

    public ErrorMessageResponse(String message) {
        this(message, message);
    }
}
