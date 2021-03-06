/*
 * Copyright 2015 floragunn UG (haftungsbeschränkt)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.ssl.transport;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.concurrent.Callable;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.auth.x500.X500Principal;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.netty.NettyTransportChannel;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.ssl.SslHandler;

public class SearchGuardSSLTransportService extends TransportService {

    @Inject
    public SearchGuardSSLTransportService(final Settings settings, final Transport transport, final ThreadPool threadPool) {
        super(settings, transport, threadPool);
    }

    @Override
    public <Request extends TransportRequest> void registerRequestHandler(final String action, final Callable<Request> requestFactory,
            final String executor, final TransportRequestHandler<Request> handler) {
        super.registerRequestHandler(action, requestFactory, executor, new Interceptor<Request>(handler, action));
    }

    @Override
    public <Request extends TransportRequest> void registerRequestHandler(final String action, final Class<Request> request,
            final String executor, final boolean forceExecution, final TransportRequestHandler<Request> handler) {
        super.registerRequestHandler(action, request, executor, forceExecution, new Interceptor<Request>(handler, action));
    }

    private class Interceptor<Request extends TransportRequest> implements TransportRequestHandler<Request> {

        private final ESLogger log = Loggers.getLogger(this.getClass());
        private final TransportRequestHandler<Request> handler;
        private final String action;

        public Interceptor(final TransportRequestHandler<Request> handler, final String acion) {
            super();
            this.handler = handler;
            this.action = acion;
        }

        @Override
        public void messageReceived(final Request request, final TransportChannel transportChannel) throws Exception {

            if (!(transportChannel instanceof NettyTransportChannel)) {
                messageReceivedDecorate(request, handler, transportChannel);
                return;
            }

            try {
                final Channel channel = ((NettyTransportChannel) transportChannel).getChannel();
                final SslHandler sslhandler = (SslHandler) channel.getPipeline().get("ssl_server");

                if (sslhandler == null) {
                    final String msg = "No ssl handler found";
                    log.error(msg);
                    final Exception exception = new ElasticsearchException(msg);
                    transportChannel.sendResponse(exception);
                    throw exception;
                }

                X500Principal principal;

                final Certificate[] certs = sslhandler.getEngine().getSession().getPeerCertificates();

                if (certs != null && certs.length > 0 && certs instanceof X509Certificate[]) {
                    addAdditionalContextValues(action, request, (X509Certificate[]) certs);
                    principal = ((X509Certificate) certs[0]).getSubjectX500Principal();
                    request.putInContext("_sg_ssl_transport_principal", principal == null ? null : principal.getName());
                    request.putInContext("_sg_ssl_transport_peer_certificates", certs);
                    request.putInContext("_sg_ssl_transport_protocol", sslhandler.getEngine().getSession().getProtocol());
                    request.putInContext("_sg_ssl_transport_cipher", sslhandler.getEngine().getSession().getCipherSuite());
                    messageReceivedDecorate(request, handler, transportChannel);
                } else {
                    final String msg = "No transport client certificates found (SG 12)";
                    log.error(msg);
                    final Exception exception = new ElasticsearchException(msg);
                    transportChannel.sendResponse(exception);
                    throw exception;
                }

            } catch (final SSLPeerUnverifiedException e) {
                log.error("Can not verify SSL peer (SG 13) due to {}", e, e);
                final Exception exception = ExceptionsHelper.convertToElastic(e);
                transportChannel.sendResponse(exception);
                throw exception;
            }
        }

    }

    protected void addAdditionalContextValues(final String action, final TransportRequest request, final X509Certificate[] certs)
            throws Exception {
        // no-op
    }
    
    protected void messageReceivedDecorate(final TransportRequest request, final TransportRequestHandler handler, final TransportChannel transportChannel) throws Exception {
        handler.messageReceived(request, transportChannel);
    }
}
