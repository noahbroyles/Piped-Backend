package me.kavin.piped.utils;

import java.util.Map;
import java.util.List;
import org.schabi.newpipe.extractor.Page;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Jackson mix-in for NewPipe's {@link Page}. Jackson applies a mix-in's annotations to the target
 * class but never runs the mix-in's own code, so the validation lives in {@link PageDeserializer},
 * which this annotation makes Jackson use for every Page it reads.
 */
@JsonDeserialize(using = PageDeserializer.class)
public abstract class PageMixin extends Page {

    // Never called: it only exists because Page has no no-argument constructor to inherit.
    public PageMixin(String url, String id, List<String> ids, Map<String, String> cookies, byte[] body) {
        super(url, id, ids, cookies, body);
    }
}
