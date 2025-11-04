package com.mimecast.robin.mime.headers;

/**
 * Header tag container for tagging header values.
 * <p>
 * This class holds a header name and a tag string that should be prepended to the header value.
 */
public class HeaderTag {

    /**
     * Header name to be tagged.
     */
    private final String headerName;

    /**
     * Tag string to be prepended to the header value.
     */
    private final String tag;

    /**
     * Constructs a new HeaderTag instance.
     *
     * @param headerName Header name to be tagged.
     * @param tag        Tag string to be prepended.
     */
    public HeaderTag(String headerName, String tag) {
        this.headerName = headerName;
        this.tag = tag;
    }

    /**
     * Gets the header name.
     *
     * @return Header name.
     */
    public String getHeaderName() {
        return headerName;
    }

    /**
     * Gets the tag.
     *
     * @return Tag string.
     */
    public String getTag() {
        return tag;
    }
}
