/*
 * Licensed to the Indoqa Software Design und Beratung GmbH (Indoqa) under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Indoqa licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.indoqa.idqsa;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.indoqa.idqsa.openapi.ApiClient;
import com.indoqa.idqsa.openapi.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.indoqa.idqsa.openapi.api.TrackingApi;
import com.indoqa.idqsa.openapi.model.TrackingInput;

public final class TrackingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrackingClient.class);

    private static final int TIMEOUT_SECONDS = 5;
    private static final int DELAY_AFTER_ERROR = 30_000;
    private static final int DELAY_INTERVAL = 100;
    private static final int MAX_FAILED = 10;

    private static final String GIT_PROPERTIES = "idqsa-git.properties";
    private static final String UNKNOWN_VERSION = "unknown/unknown";

    private final TrackingApi trackingApi;

    private final Queue<Envelope> pending = new ConcurrentLinkedDeque<>();
    private ApiClient apiClient;

    public TrackingClient() {
        super();

        String baseUri = System.getProperty("search-analytics.base-uri");
        if (baseUri == null || baseUri.isBlank()) {
            baseUri = "https://idqsa.com";
        }

        this.apiClient = new ApiClient();
        this.apiClient.updateBaseUri(baseUri);
        this.apiClient.setConnectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS));
        this.apiClient.setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS));
        this.apiClient.setRequestInterceptor(new GzipRequestInterceptor());
        this.trackingApi = new TrackingApi(this.apiClient);

        Thread thread = new Thread(this::sendPending, "Tracking Client Sender");
        thread.setDaemon(true);
        thread.start();
    }

    private static void delay() {
        try {
            Thread.sleep(DELAY_INTERVAL);
        } catch (InterruptedException e) {
            // do nothing
        }
    }

    private static void delayAfterError() {
        try {
            Thread.sleep(DELAY_AFTER_ERROR);
        } catch (InterruptedException e) {
            // do nothing
        }
    }

    private static String getGitVersion() {
        Properties properties = new Properties();
        try (InputStream gitPropertiesStream = TrackingClient.class.getClassLoader().getResourceAsStream(GIT_PROPERTIES)) {
            if (gitPropertiesStream == null) {
                LOGGER.warn("Resource {} not found", GIT_PROPERTIES);
                return UNKNOWN_VERSION;
            }
            properties.load(gitPropertiesStream);
            String abbrev = properties.getProperty("git.commit.id.abbrev", "unknown");
            String time = properties.getProperty("git.build.time", "unknown");
            return abbrev + "|" + time;
        } catch (Exception e) {
            LOGGER.error("Cannot read {}", GIT_PROPERTIES, e);
            return UNKNOWN_VERSION;
        }
    }

    private static String getClientVersion() {
        return "idqsa/java: " + getGitVersion();
    }

    /**
     * Add the given <code>trackingInput</code> to the internal list of pending payloads.
     * <p>
     * Sending will happen asynchronously in the background. If communication with the Tracking API fails, a limited number of retries
     * will be attempted before silently dropping the {@link TrackingInput}.
     *
     * @param trackingInput The {@link TrackingInput} to send.
     * @param userAgent An optional value for the HTTP "UserAgent" that can be overwritten when sending the TrackingInput.
     */
    public void addTrackingInput(TrackingInput trackingInput, String userAgent) {
        this.pending.add(new Envelope(trackingInput, userAgent));

        this.wakeForInput();
    }

    /**
     * Send the given <code>TrackingInput</code> immediately.
     *
     * Sending will happen synchronously and delay the caller until the payload was accepted or an error occurred.
     *
     * @param trackingInput The <code>TrackingInput</code> to send.
     * @param userAgent An optional value for the HTTP "UserAgent" that can be overwritten when sending the TrackingInput.
     *
     * @throws ApiException If communication with the Tracking API fails or the TrackingInput was not accepted.
     */
    public void sendTrackingInput(TrackingInput trackingInput, String userAgent) throws ApiException {
        trackingInput.setClientVersion(getClientVersion());

        try {
            String body = this.apiClient.getObjectMapper().writeValueAsString(trackingInput);
            this.trackingApi.postTrackingInput(userAgent, body);
        } catch (JsonProcessingException e) {
            throw new ApiException(e);
        }
    }

    private void sendPending() {
        while (true) {
            Envelope envelope = this.pending.poll();
            if (envelope == null) {
                this.waitForInput();
                continue;
            }

            if (envelope.getDelayUntil() != null && System.currentTimeMillis() < envelope.getDelayUntil()) {
                this.pending.add(envelope);
                delay();
                continue;
            }

            this.sendTrackingInput(envelope);
        }
    }

    private void sendTrackingInput(Envelope envelope) {
        TrackingInput trackingInput = envelope.getTrackingInput();

        try {
            this.sendTrackingInput(trackingInput, envelope.getUserAgent());
        } catch (ApiException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JsonProcessingException) {
                LOGGER.error("JSON serialization failed", e);

                // don't re-schedule
                delayAfterError();
            } else if (e.getCode() == 0) {
                // not HTTP response code
                LOGGER.error("Tracking API is not available", e);
                this.pending.add(envelope);
                delayAfterError();
            } else {
                envelope.incrementFailed();

                if (envelope.getFailed() >= MAX_FAILED) {
                    LOGGER.error("TrackingInput failed {} times. Dropping it now.", envelope.getFailed());
                } else {
                    LOGGER.error("Could not send TrackingInput.", e);
                    envelope.setDelayUntil(System.currentTimeMillis() + DELAY_AFTER_ERROR);
                    this.pending.add(envelope);
                }
            }
        }
    }

    private synchronized void waitForInput() {
        try {
            this.wait();
        } catch (InterruptedException e) {
            // do nothing
        }
    }

    private synchronized void wakeForInput() {
        this.notifyAll();
    }

    private static class Envelope {

        private final TrackingInput trackingInput;
        private final String userAgent;

        private int failed;
        private Long delayUntil;

        public Envelope(TrackingInput trackingInput, String userAgent) {
            super();
            this.trackingInput = trackingInput;
            this.userAgent = userAgent;
        }

        public Long getDelayUntil() {
            return this.delayUntil;
        }

        public int getFailed() {
            return this.failed;
        }

        public TrackingInput getTrackingInput() {
            return this.trackingInput;
        }

        public String getUserAgent() {
            return this.userAgent;
        }

        public void incrementFailed() {
            this.failed++;
        }

        public void setDelayUntil(Long delayUntil) {
            this.delayUntil = delayUntil;
        }
    }
}
