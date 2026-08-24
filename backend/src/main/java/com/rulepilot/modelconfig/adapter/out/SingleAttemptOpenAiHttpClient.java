package com.rulepilot.modelconfig.adapter.out;

import com.openai.core.RequestOptions;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpObservationInterceptor;
import io.micrometer.observation.ObservationRegistry;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;

/** OpenAI-compatible transport whose request boundary is exactly one HTTP exchange. */
final class SingleAttemptOpenAiHttpClient implements HttpClient {

    private final OkHttpClient transport;
    private final com.openai.client.okhttp.OkHttpClient delegate;

    SingleAttemptOpenAiHttpClient(Duration requestTimeout, ObservationRegistry observations) {
        transport = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(requestTimeout)
                .readTimeout(requestTimeout)
                .writeTimeout(requestTimeout)
                .callTimeout(requestTimeout)
                .addInterceptor(OkHttpObservationInterceptor.builder(observations, "okhttp.requests")
                        .build())
                .build();
        delegate = new com.openai.client.okhttp.OkHttpClient(transport);
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        return delegate.execute(oneShot(request), requestOptions);
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
        return delegate.executeAsync(oneShot(request), requestOptions);
    }

    @Override
    public void close() {
        delegate.close();
    }

    OkHttpClient transport() {
        return transport;
    }

    private HttpRequest oneShot(HttpRequest request) {
        HttpRequestBody body = request.body();
        if (body == null || !body.repeatable()) {
            return request;
        }
        return request.toBuilder().body(new OneShotBody(body)).build();
    }

    private record OneShotBody(HttpRequestBody source) implements HttpRequestBody {

        @Override
        public void writeTo(OutputStream output) {
            source.writeTo(output);
        }

        @Override
        public String contentType() {
            return source.contentType();
        }

        @Override
        public long contentLength() {
            return source.contentLength();
        }

        @Override
        public boolean repeatable() {
            return false;
        }

        @Override
        public void close() {
            source.close();
        }
    }
}
