package me.kavin.piped.utils.obj;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Everything a SABR client (such as googlevideo's SabrStreamingAdapter) needs to play a video,
 * taken from a WEB client player response.
 */
@AllArgsConstructor
@NoArgsConstructor
public class SabrInfo {

    // The streaming URL, already pointed at the proxy
    public String serverAbrStreamingUrl;

    // Opaque base64 blob that has to be sent back with every SABR request
    public String videoPlaybackUstreamerConfig;

    // Content-bound PoToken for the SABR requests, or null without a bg-helper
    public String poToken;

    // The client the player response was fetched with, which SABR requests must claim to be
    public int clientName;
    public String clientVersion;

    // The adaptive formats exactly as YouTube returned them (itag, lastModified, xtags, ...)
    public List<Map<String, Object>> formats;
}
