package me.kavin.piped.utils;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonBuilder;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import me.kavin.piped.consts.Constants;
import me.kavin.piped.utils.obj.SabrInfo;
import org.apache.commons.lang3.StringUtils;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import rocks.kavin.reqwest4j.ReqwestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static me.kavin.piped.consts.Constants.mapper;
import static me.kavin.piped.utils.URLUtils.rewriteVideoURL;
import static org.schabi.newpipe.extractor.NewPipe.*;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.*;

/**
 * Fetches what a SABR client needs to play a video.
 * <p>
 * NewPipeExtractor only takes streams from mobile clients, which still return plain URLs. YouTube's
 * web player streams with SABR instead, so this makes a WEB client player request of its own and
 * keeps the parts of it that a SABR client needs.
 */
public class SabrHelper {

    // Innertube ID of the WEB client, which SABR requests have to name in their client info
    private static final int WEB_CLIENT_NAME_ID = 1;

    // Format fields that point at googlevideo directly; SABR formats normally don't have them anyway
    private static final List<String> DIRECT_URL_FIELDS = List.of("url", "signatureCipher", "cipher");

    /**
     * @return the SABR info for {@code videoId}, or null if SABR is disabled or YouTube didn't offer it
     */
    public static SabrInfo getSabrInfo(String videoId) throws Exception {
        // NewPipeExtractor can't decipher the n parameter of current players, and googlevideo
        // rejects SABR URLs without it, so SABR needs the player n decoder
        if (Constants.DISABLE_SABR || StringUtils.isEmpty(Constants.PLAYER_N_DECODER_URL))
            return null;

        // Mint the PoToken while we fetch visitor data, as the bg-helper takes a moment
        var futurePoToken = Multithreading.supplyAsync(() -> {
            if (StringUtils.isEmpty(Constants.BG_HELPER_URL))
                return null;
            try {
                // SABR wants a content-bound token, i.e. one bound to the video ID
                return BgPoTokenProvider.generatePoToken(Constants.BG_HELPER_URL, videoId);
            } catch (Exception e) {
                ExceptionHandler.handle(e);
                return null;
            }
        });

        final Localization localization = getPreferredLocalization();
        final ContentCountry contentCountry = getPreferredContentCountry();

        final var requestInfo = InnertubeClientRequestInfo.ofWebClient();
        requestInfo.clientInfo.clientVersion = getClientVersion();

        final Map<String, List<String>> headers = getYouTubeHeaders();

        // YouTube only answers player requests properly with a visitor ID
        requestInfo.clientInfo.visitorData = getVisitorDataFromInnertube(requestInfo, localization, contentCountry,
                headers, YOUTUBEI_V1_URL, null, false);

        final String poToken = futurePoToken.get();

        // The URL has to be deciphered by the same player version the request says it uses
        final int signatureTimestamp = RequestUtils.sendGetJson(Constants.PLAYER_N_DECODER_URL + "/sts").get()
                .get("signatureTimestamp").asInt();

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry, requestInfo, null)
                .value(VIDEO_ID, videoId)
                .value(CONTENT_CHECK_OK, true)
                .value(RACY_CHECK_OK, true)
                .object("playbackContext")
                .object("contentPlaybackContext")
                // Ties the response to the player version that deciphers the n parameter below
                .value("signatureTimestamp", signatureTimestamp)
                .value("referer", "https://www.youtube.com/watch?v=" + videoId)
                .end()
                .end();

        if (poToken != null) {
            builder.object("serviceIntegrityDimensions")
                    .value("poToken", poToken)
                    .end();
        }

        final byte[] body = JsonWriter.string(builder.done()).getBytes(StandardCharsets.UTF_8);

        final JsonObject playerResponse = JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(YOUTUBEI_V1_URL + "player?" + DISABLE_PRETTY_PRINT_PARAMETER,
                        headers, body, localization)));

        // NewPipeExtractor reports why a video can't be played, so just bow out here
        if (!"OK".equals(playerResponse.getObject("playabilityStatus").getString("status")))
            return null;

        final JsonObject streamingData = playerResponse.getObject("streamingData");

        final String streamingUrl = streamingData.getString("serverAbrStreamingUrl");
        final String ustreamerConfig = playerResponse.getObject("playerConfig")
                .getObject("mediaCommonConfig")
                .getObject("mediaUstreamerRequestConfig")
                .getString("videoPlaybackUstreamerConfig");

        if (StringUtils.isEmpty(streamingUrl) || StringUtils.isEmpty(ustreamerConfig))
            return null;

        // Without a deciphered n parameter, googlevideo answers SABR requests with a 403
        final String deobfuscatedUrl = decipher(streamingUrl, signatureTimestamp);

        final JsonArray adaptiveFormats = streamingData.getArray("adaptiveFormats");
        final List<Map<String, Object>> formats = adaptiveFormats.stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(format -> {
                    var copy = new JsonObject(format);
                    DIRECT_URL_FIELDS.forEach(copy::remove);
                    return (Map<String, Object>) copy;
                })
                .toList();

        if (formats.isEmpty())
            return null;

        return new SabrInfo(
                rewriteVideoURL(deobfuscatedUrl, Map.of()),
                ustreamerConfig,
                poToken,
                WEB_CLIENT_NAME_ID,
                requestInfo.clientInfo.clientVersion,
                formats
        );
    }

    /**
     * Has the player n decoder run YouTube's player code on the n parameter of {@code url}.
     */
    private static String decipher(String url, int signatureTimestamp) throws Exception {
        var response = ReqwestUtils.fetch(Constants.PLAYER_N_DECODER_URL + "/decipher", "POST",
                mapper.writeValueAsBytes(mapper.createObjectNode()
                        .put("url", url)
                        .put("signatureTimestamp", signatureTimestamp)),
                Map.of("Content-Type", "application/json")).get();

        if (response.status() != 200)
            throw new RuntimeException("Player n decoder could not decipher the streaming URL: " + new String(response.body(), StandardCharsets.UTF_8));

        return mapper.readTree(response.body()).get("url").asText();
    }
}
