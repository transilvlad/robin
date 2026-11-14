package com.mimecast.robin.http;

/**
 * Builds HttpResponse instances for tests outside this package.
 */
public final class HttpResponseFixture {

    private HttpResponseFixture() {
    }

    /**
     * Builds a response.
     *
     * @param status      HTTP status code.
     * @param contentType Content-Type header or null.
     * @param body        Body or null.
     * @return HttpResponse instance.
     */
    public static HttpResponse of(int status, String contentType, String body) {
        HttpResponse response = new HttpResponse()
                .setSuccess(status >= 200 && status < 300)
                .setStatusCode(status)
                .addBody(body);
        if (contentType != null) {
            response.addHeader("Content-Type", contentType);
        }
        return response;
    }
}
