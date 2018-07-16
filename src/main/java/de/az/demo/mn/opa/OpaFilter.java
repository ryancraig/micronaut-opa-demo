package de.az.demo.mn.opa;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;

import static io.micronaut.http.MediaType.TEXT_PLAIN;

/**
 * Server Filter backed by OPA.
 */
@Filter("/protected/**")
public class OpaFilter implements HttpServerFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpaFilter.class);

    private final OpaDataClient opaClient;

    /**
     * Constructor.
     *
     * @param opaClient OPA client
     */
    @Inject
    public OpaFilter(OpaDataClient opaClient) {
        this.opaClient = opaClient;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Flowable.fromCallable(() -> {
            // Prepare the input structure for OPA
            // Decisions can be made on arbitrary request properties, they just need to be passed to OPA
            Map<String, Object> input = new HashMap<>();
            input.put("headers", request.getHeaders().asMap());
            input.put("method", request.getMethod().toString());
            input.put("path", request.getPath());
            return input;
        }).flatMapSingle(
                // Call OPA
                input -> opaClient.isMnDemoAllowed(input)
                        .doOnError(e -> LOGGER.error("Unable to communicate with OPA", e))
                        .map(OpaDataResponse::getResult)
                        .doOnSuccess(r -> LOGGER.info("On {}, OPA says {}", request.getUri(), r))
                        .onErrorReturnItem(false)
        ).switchMap(
                // Process the outcome from OPA by either letting the request pass or returning HTTP 401 Unauthorized
                allowed -> allowed ? chain.proceed(request) : Flowable.fromCallable(
                        () -> HttpResponse.unauthorized()
                                .contentType(TEXT_PLAIN)
                                .body("You are not authorized. Use /free, you filthy peasant!")
                ));
    }

}