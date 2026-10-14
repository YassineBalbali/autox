package com.autox.config;

import com.autox.api.error.ErrorCode;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.HttpStatus;

/**
 * Answers the requests that never reach Spring in the same shape as the ones that do.
 *
 * <p>A request the HTTP parser itself rejects — an illegal character in the path, a header
 * too large, something that is not HTTP at all — is refused by Tomcat before any servlet,
 * filter or exception handler exists to see it. Tomcat's own answer is an HTML page in a
 * style anyone recognises. It carried no stack trace or version (Spring Boot switches both
 * off), but it was the one response from this API that was not
 * {@code application/problem+json}, and the one that announced what the server is.
 *
 * <p>The body is built by hand, from constants and a status code. Nothing from the request
 * is echoed into it — the request is, by definition here, the thing that could not be
 * trusted to be parsed.
 */
public class ProblemJsonErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();

        // The same three conditions the stock valve checks: an error, nothing written yet,
        // and nobody else has reported it.
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }

        try {
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("utf-8");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body(status));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException ignored) {
            // The connection is gone or the response is committed: there is nobody to tell.
        }
    }

    static String body(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        String title = known == null ? "Error" : known.getReasonPhrase();
        ErrorCode code;
        String detail;
        if (status >= 500) {
            code = ErrorCode.INTERNAL_ERROR;
            detail = "The request could not be processed.";
        } else if (status == 404) {
            code = ErrorCode.NOT_FOUND;
            detail = "No endpoint matches this request.";
        } else {
            code = ErrorCode.MALFORMED_REQUEST;
            detail = "The request could not be read.";
        }
        // Every value above is a constant of this application; none needs escaping.
        return "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code.name()
                + "\",\"timestamp\":\"" + Instant.now() + "\"}";
    }
}
