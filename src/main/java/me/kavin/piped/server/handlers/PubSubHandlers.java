package me.kavin.piped.server.handlers;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import io.sentry.Sentry;
import me.kavin.piped.consts.Constants;
import me.kavin.piped.utils.*;
import me.kavin.piped.utils.obj.MatrixHelper;
import me.kavin.piped.utils.obj.federation.FederatedVideoInfo;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.StatelessSession;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static me.kavin.piped.consts.Constants.YOUTUBE_SERVICE;

public class PubSubHandlers {

    private static final String TOPIC_PREFIX = "https://www.youtube.com/xml/feeds/videos.xml?channel_id=";
    private static final Pattern VIDEO_ID = Pattern.compile("[a-zA-Z\\d_-]{11}");

    // A notification normally holds a single video, so anything larger is not from the hub
    private static final int MAX_ENTRIES_PER_NOTIFICATION = 50;
    private static final int MAX_QUEUE_SIZE = 10_000;

    private static final LinkedBlockingQueue<String> pubSubQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    /**
     * Handles the hub's verification of intent. Only confirms subscriptions that this instance requested.
     */
    public static boolean verifyIntent(String mode, String topic) {

        if (!"subscribe".equals(mode) || topic == null || !topic.startsWith(TOPIC_PREFIX))
            return false;

        String channelId = topic.substring(TOPIC_PREFIX.length());

        if (!ChannelHelpers.isValidId(channelId) || DatabaseHelper.getPubSubFromId(channelId) == null)
            return false;

        PubSubHelper.updatePubSub(channelId);
        return true;
    }

    /**
     * Checks the hub's X-Hub-Signature header (an HMAC of the body, keyed with the secret
     * sent when subscribing). Always true if no secret is configured.
     */
    public static boolean isSignatureValid(byte[] body, String signature) {

        if (Constants.PUBSUB_SECRET == null)
            return true;

        if (signature == null)
            return false;

        String algorithm = StringUtils.substringBefore(signature, "=");
        String received = StringUtils.substringAfter(signature, "=");

        HmacAlgorithms hmac = switch (algorithm) {
            case "sha1" -> HmacAlgorithms.HMAC_SHA_1;
            case "sha256" -> HmacAlgorithms.HMAC_SHA_256;
            case "sha384" -> HmacAlgorithms.HMAC_SHA_384;
            case "sha512" -> HmacAlgorithms.HMAC_SHA_512;
            default -> null;
        };

        if (hmac == null)
            return false;

        String expected = new HmacUtils(hmac, Constants.PUBSUB_SECRET).hmacHex(body);

        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                received.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public static void handlePubSub(byte[] body) throws Exception {
        SyndFeed feed = new SyndFeedInput().build(new InputSource(new ByteArrayInputStream(body)));

        int count = 0;

        for (var entry : feed.getEntries()) {

            if (++count > MAX_ENTRIES_PER_NOTIFICATION)
                break;

            if (entry.getLinks().isEmpty() || entry.getPublishedDate() == null)
                continue;

            String url = entry.getLinks().get(0).getHref();
            String videoId = StringUtils.substring(url, -11);

            if (videoId == null || !VIDEO_ID.matcher(videoId).matches())
                continue;

            long publishedDate = entry.getPublishedDate().getTime();

            String str = videoId + ":" + publishedDate;

            if (pubSubQueue.contains(str))
                continue;

            // Drop the video if the queue is full, rather than letting it grow without bound
            pubSubQueue.offer(str);
        }
    }

    static {
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            new Thread(() -> {
                try {
                    while (true) {
                        String str = pubSubQueue.take();

                        String videoId = StringUtils.substringBefore(str, ":");
                        long publishedDate = Long.parseLong(StringUtils.substringAfter(str, ":"));

                        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
                            if (DatabaseHelper.doesVideoExist(s, videoId))
                                continue;
                        }

                        try {
                            Sentry.setExtra("videoId", videoId);
                            var extractor = YOUTUBE_SERVICE.getStreamExtractor("https://youtube.com/watch?v=" + videoId);
                            extractor.fetchPage();

                            Multithreading.runAsync(() -> {

                                DateWrapper uploadDate;

                                try {
                                    uploadDate = extractor.getUploadDate();
                                } catch (ParsingException e) {
                                    throw new RuntimeException(e);
                                }

                                if (uploadDate != null && System.currentTimeMillis() - uploadDate.offsetDateTime().toInstant().toEpochMilli() < TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION)) {
                                    try {
                                        MatrixHelper.sendEvent("video.piped.stream.info", new FederatedVideoInfo(
                                                StringUtils.substring(extractor.getUrl(), -11), StringUtils.substring(extractor.getUploaderUrl(), -24),
                                                extractor.getName(),
                                                extractor.getLength(), extractor.getViewCount())
                                        );
                                    } catch (Exception e) {
                                        ExceptionHandler.handle(e);
                                    }
                                }
                            });

                            VideoHelpers.handleNewVideo(extractor, publishedDate, null);
                        } catch (Exception e) {
                            ExceptionHandler.handle(e);
                        }
                    }
                } catch (Exception e) {
                    ExceptionHandler.handle(e);
                }
            }, "PubSub-Worker-" + i).start();
        }
    }

}
