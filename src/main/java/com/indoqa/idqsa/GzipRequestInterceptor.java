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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipRequestInterceptor implements Consumer<HttpRequest.Builder> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GzipRequestInterceptor.class);

    @Override
    public void accept(Builder builder) {
        HttpRequest request = builder.build();
        BodyPublisher bodyPublisher = request.bodyPublisher().get();
        bodyPublisher.subscribe(new GzipSubscriber(builder, request.method()));
    }

    private static class GzipSubscriber implements Subscriber<ByteBuffer> {

        private final Builder builder;
        private final String method;

        private ByteArrayOutputStream baos;
        private GZIPOutputStream gzipOutputStream;

        private Subscription subscription;

        public GzipSubscriber(Builder builder, String method) {
            this.builder = builder;
            this.method = method;
        }

        @Override
        public void onComplete() {
            try {
                this.gzipOutputStream.close();

                byte[] data = this.baos.toByteArray();
                this.builder.method(this.method, BodyPublishers.ofByteArray(data));
                this.builder.header("Content-Encoding", "gzip");
            } catch (IOException e) {
                LOGGER.error("Failed to complete GZIP compression. Body will remain uncompressed.", e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            LOGGER.error("Received an upstream error. Body will remain uncompressed.", throwable);
        }

        @Override
        public void onNext(ByteBuffer item) {
            try {
                while (item.hasRemaining()) {
                    this.gzipOutputStream.write(item.get());
                }
            } catch (IOException e) {
                LOGGER.error("Failed to compress the next chunk. Body will remain uncompressed.", e);
                // don't send any more elements
                // onComplete will not be called
                this.subscription.cancel();
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;

            try {
                this.baos = new ByteArrayOutputStream();
                this.gzipOutputStream = new GZIPOutputStream(this.baos);

                subscription.request(Integer.MAX_VALUE);
            } catch (IOException e) {
                LOGGER.error("Failed to construct GZIPOutputStream. Body will remain uncompressed.", e);
            }
        }
    }
}
