package me.kavin.piped.utils;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.net.URISyntaxException;
import org.schabi.newpipe.extractor.Page;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import me.kavin.piped.utils.resp.InvalidRequestResponse;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Reads NewPipe {@link Page} objects (the nextpage tokens clients send back to the API) and rejects
 * any whose URL does not point at YouTube.
 * <p>
 * Some extractors (YouTube Mix playlists and YouTube Music search) send their request to the
 * token's URL. Without this check, a crafted token could make the backend send requests to any
 * address it can reach. Registered for Page through {@link PageMixin}.
 */
public class PageDeserializer extends StdDeserializer<Page> {

    // The only hosts NewPipeExtractor builds YouTube page tokens for: www.youtube.com and
    // youtubei.googleapis.com (InnerTube API), music.youtube.com (YouTube Music search), and
    // youtube.com / m.youtube.com watch URLs (comments).
    private static final Set<String> YOUTUBE_HOSTS = Set.of(
            "www.youtube.com", "youtube.com", "m.youtube.com", "music.youtube.com", "youtubei.googleapis.com");

    public PageDeserializer() {
        super(Page.class);
    }

    @Override
    public Page deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = context.readTree(parser);

        // The same fields, in the same JSON shape, as Page's own constructor (see PageMixin).
        String url = textOrNull(node.get("url"));
        String id = textOrNull(node.get("id"));
        JavaType listType = context.getTypeFactory().constructCollectionType(List.class, String.class);
        JavaType mapType = context.getTypeFactory().constructMapType(Map.class, String.class, String.class);
        List<String> ids = node.hasNonNull("ids") ? context.readTreeAsValue(node.get("ids"), listType) : null;
        Map<String, String> cookies = node.hasNonNull("cookies") ? context.readTreeAsValue(node.get("cookies"), mapType) : null;
        // Jackson writes byte[] as Base64 text, which binaryValue() decodes.
        byte[] body = node.hasNonNull("body") ? node.get("body").binaryValue() : null;

        return new Page(requireYouTubeUrl(url), id, ids, cookies, body);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String requireYouTubeUrl(String url) {
        // A token without a URL never makes an extractor send a request.
        if (url == null)
            return null;

        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            boolean isYouTube = "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && host != null
                    && YOUTUBE_HOSTS.contains(host.toLowerCase(Locale.ROOT));
            if (isYouTube)
                return url;
        } catch (URISyntaxException ignored) {
            // Not a valid URI, so it is rejected below.
        }

        ExceptionHandler.throwErrorResponse(new InvalidRequestResponse("Invalid nextpage provided"));
        return null;
    }
}
