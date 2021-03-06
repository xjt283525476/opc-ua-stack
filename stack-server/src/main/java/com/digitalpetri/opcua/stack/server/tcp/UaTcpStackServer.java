package com.digitalpetri.opcua.stack.server.tcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.digitalpetri.opcua.stack.core.Stack;
import com.digitalpetri.opcua.stack.core.StatusCodes;
import com.digitalpetri.opcua.stack.core.UaException;
import com.digitalpetri.opcua.stack.core.application.CertificateManager;
import com.digitalpetri.opcua.stack.core.application.UaStackServer;
import com.digitalpetri.opcua.stack.core.application.services.AttributeServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.DiscoveryServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.MethodServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.MonitoredItemServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.NodeManagementServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.QueryServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.ServiceRequest;
import com.digitalpetri.opcua.stack.core.application.services.ServiceRequestHandler;
import com.digitalpetri.opcua.stack.core.application.services.ServiceResponse;
import com.digitalpetri.opcua.stack.core.application.services.SessionServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.SubscriptionServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.TestServiceSet;
import com.digitalpetri.opcua.stack.core.application.services.ViewServiceSet;
import com.digitalpetri.opcua.stack.core.channel.ChannelConfig;
import com.digitalpetri.opcua.stack.core.channel.ServerSecureChannel;
import com.digitalpetri.opcua.stack.core.security.SecurityPolicy;
import com.digitalpetri.opcua.stack.core.serialization.UaRequestMessage;
import com.digitalpetri.opcua.stack.core.serialization.UaResponseMessage;
import com.digitalpetri.opcua.stack.core.types.builtin.ByteString;
import com.digitalpetri.opcua.stack.core.types.builtin.LocalizedText;
import com.digitalpetri.opcua.stack.core.types.enumerated.ApplicationType;
import com.digitalpetri.opcua.stack.core.types.enumerated.MessageSecurityMode;
import com.digitalpetri.opcua.stack.core.types.structured.ApplicationDescription;
import com.digitalpetri.opcua.stack.core.types.structured.EndpointDescription;
import com.digitalpetri.opcua.stack.core.types.structured.FindServersRequest;
import com.digitalpetri.opcua.stack.core.types.structured.FindServersResponse;
import com.digitalpetri.opcua.stack.core.types.structured.GetEndpointsRequest;
import com.digitalpetri.opcua.stack.core.types.structured.GetEndpointsResponse;
import com.digitalpetri.opcua.stack.core.types.structured.SignedSoftwareCertificate;
import com.digitalpetri.opcua.stack.core.types.structured.UserTokenPolicy;
import com.digitalpetri.opcua.stack.server.Endpoint;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.digitalpetri.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;

public class UaTcpStackServer implements UaStackServer {

    /**
     * The {@link AttributeKey} that maps to the {@link Channel} bound to a {@link ServerSecureChannel}.
     */
    public static final AttributeKey<Channel> BoundChannelKey = AttributeKey.valueOf("bound-channel");

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final AtomicLong channelIds = new AtomicLong();
    private final AtomicLong tokenIds = new AtomicLong();

    private final Map<Class<? extends UaRequestMessage>, ServiceRequestHandler<UaRequestMessage, UaResponseMessage>>
            handlers = Maps.newConcurrentMap();

    private final Map<Long, ServerSecureChannel> secureChannels = Maps.newConcurrentMap();

    private final ListMultimap<Long, ServiceResponse> responseQueues =
            Multimaps.synchronizedListMultimap(ArrayListMultimap.create());

    private final List<Endpoint> endpoints = Lists.newCopyOnWriteArrayList();
    private final Set<String> discoveryUrls = Sets.newConcurrentHashSet();

    private final HashedWheelTimer wheelTimer = Stack.sharedWheelTimer();
    private final Map<Long, Timeout> timeouts = Maps.newConcurrentMap();

    private final String serverName;
    private final LocalizedText applicationName;
    private final String applicationUri;
    private final String productUri;
    private final CertificateManager certificateManager;
    private final ExecutorService executor;
    private final List<UserTokenPolicy> userTokenPolicies;
    private final List<SignedSoftwareCertificate> softwareCertificates;
    private final ChannelConfig channelConfig;

    public UaTcpStackServer(String serverName,
                            LocalizedText applicationName,
                            String applicationUri,
                            String productUri,
                            CertificateManager certificateManager,
                            ExecutorService executor,
                            List<UserTokenPolicy> userTokenPolicies,
                            List<SignedSoftwareCertificate> softwareCertificates,
                            ChannelConfig channelConfig) {

        this.serverName = serverName;
        this.applicationName = applicationName;
        this.applicationUri = applicationUri;
        this.productUri = productUri;
        this.certificateManager = certificateManager;
        this.executor = executor;
        this.userTokenPolicies = userTokenPolicies;
        this.softwareCertificates = softwareCertificates;
        this.channelConfig = channelConfig;

        addServiceSet(new DefaultDiscoveryServiceSet());

        addServiceSet(new AttributeServiceSet() {
        });
        addServiceSet(new MethodServiceSet() {
        });
        addServiceSet(new MonitoredItemServiceSet() {
        });
        addServiceSet(new NodeManagementServiceSet() {
        });
        addServiceSet(new QueryServiceSet() {
        });
        addServiceSet(new SessionServiceSet() {
        });
        addServiceSet(new SubscriptionServiceSet() {
        });
        addServiceSet(new TestServiceSet() {
        });
        addServiceSet(new ViewServiceSet() {
        });
    }

    @Override
    public void startup() {
        for (Endpoint endpoint : endpoints) {
            try {
                URI endpointUri = endpoint.getEndpointUri();
                String bindAddress = endpoint.getBindAddress().orElse(endpointUri.getHost());

                SocketServer socketServer = SocketServer.boundTo(bindAddress, endpointUri.getPort());

                logger.info("{} bound to {} [{}/{}]",
                        endpoint.getEndpointUri(), socketServer.getLocalAddress(),
                        endpoint.getSecurityPolicy(), endpoint.getMessageSecurity());

                addDiscoveryUrl(endpointUri);

                socketServer.addServer(this);
            } catch (Exception e) {
                logger.error("Error binding {}: {}.", endpoint, e.getMessage(), e);
            }
        }
    }

    private void addDiscoveryUrl(URI endpointUri) {
        StringBuilder discoveryUrl = new StringBuilder();

        discoveryUrl.append("opc.tcp://").append(endpointUri.getHost()).append(":").append(endpointUri.getPort());
        if (!serverName.isEmpty()) {
            discoveryUrl.append("/").append(serverName);
        }

        discoveryUrls.add(discoveryUrl.toString());
    }

    @Override
    public void shutdown() {
        for (Endpoint endpoint : endpoints) {
            URI endpointUri = endpoint.getEndpointUri();
            String address = endpoint.getBindAddress().orElse(endpointUri.getHost());

            try {
                SocketServer socketServer = SocketServer.boundTo(address, endpointUri.getPort());
                socketServer.removeServer(this);
            } catch (Exception e) {
                logger.error("Error getting SocketServer for {}: {}.", endpoint, e.getMessage(), e);
            }
        }

        List<ServerSecureChannel> copy = Lists.newArrayList(secureChannels.values());
        copy.forEach(this::closeSecureChannel);
    }

    public void receiveRequest(ServiceRequest<UaRequestMessage, UaResponseMessage> serviceRequest) {
        logger.trace("Received {} on {}.", serviceRequest, serviceRequest.getSecureChannel());

        serviceRequest.getFuture().whenComplete((response, throwable) -> {
            long requestId = serviceRequest.getRequestId();

            ServiceResponse serviceResponse = response != null ?
                    new ServiceResponse(response, requestId) :
                    new ServiceResponse(serviceRequest.createServiceFault(throwable), requestId);

            ServerSecureChannel secureChannel = serviceRequest.getSecureChannel();
            boolean secureChannelValid = secureChannels.containsKey(secureChannel.getChannelId());

            if (secureChannelValid) {
                Channel channel = secureChannel.attr(BoundChannelKey).get();

                if (channel != null) {
                    if (serviceResponse.isServiceFault()) {
                        logger.debug("Sending {} on {}.", serviceResponse, secureChannel);
                    } else {
                        logger.trace("Sending {} on {}.", serviceResponse, secureChannel);
                    }
                    channel.writeAndFlush(serviceResponse, channel.voidPromise());
                } else {
                    logger.trace("Queueing {} for unbound {}.", serviceResponse, secureChannel);
                    responseQueues.put(secureChannel.getChannelId(), serviceResponse);
                }
            }
        });

        Class<? extends UaRequestMessage> requestClass = serviceRequest.getRequest().getClass();
        ServiceRequestHandler<UaRequestMessage, UaResponseMessage> handler = handlers.get(requestClass);

        try {
            if (handler != null) {
                handler.handle(serviceRequest);
            } else {
                serviceRequest.setServiceFault(StatusCodes.Bad_ServiceUnsupported);
            }
        } catch (UaException e) {
            serviceRequest.setServiceFault(e);
        } catch (Throwable t) {
            logger.error("Uncaught Throwable executing ServiceRequestHandler: {}", handler, t);
            serviceRequest.setServiceFault(StatusCodes.Bad_InternalError);
        }
    }

    @Override
    public ApplicationDescription getApplicationDescription() {
        return new ApplicationDescription(
                applicationUri,
                productUri,
                applicationName,
                ApplicationType.Server,
                null, null,
                discoveryUrls.toArray(new String[discoveryUrls.size()])
        );
    }

    public List<Endpoint> getEndpoints() {
        return endpoints;
    }

    @Override
    public EndpointDescription[] getEndpointDescriptions() {
        return getEndpoints().stream()
                .map(this::mapEndpoint)
                .toArray(EndpointDescription[]::new);
    }

    @Override
    public SignedSoftwareCertificate[] getSoftwareCertificates() {
        return softwareCertificates.toArray(new SignedSoftwareCertificate[softwareCertificates.size()]);
    }

    @Override
    public List<UserTokenPolicy> getUserTokenPolicies() {
        return userTokenPolicies;
    }

    public List<String> getEndpointUrls() {
        return endpoints.stream().map(e -> e.getEndpointUri().toString()).collect(Collectors.toList());
    }

    public Set<String> getDiscoveryUrls() {
        return discoveryUrls;
    }

    @Override
    public CertificateManager getCertificateManager() {
        return certificateManager;
    }

    @Override
    public ExecutorService getExecutorService() {
        return executor;
    }

    @Override
    public ChannelConfig getChannelConfig() {
        return channelConfig;
    }

    private long nextChannelId() {
        return channelIds.incrementAndGet();
    }

    public long nextTokenId() {
        return tokenIds.incrementAndGet();
    }

    @Override
    public ServerSecureChannel openSecureChannel() {
        ServerSecureChannel channel = new ServerSecureChannel();
        channel.setChannelId(nextChannelId());
        long channelId = channel.getChannelId();
        secureChannels.put(channelId, channel);
        return channel;
    }

    @Override
    public void closeSecureChannel(ServerSecureChannel secureChannel) {
        long channelId = secureChannel.getChannelId();

        if (secureChannels.remove(channelId) != null) {
            logger.debug("Removed secure channel id={}", channelId);
        }

        Channel channel = secureChannel.attr(BoundChannelKey).get();
        if (channel != null) {
            logger.debug("Closing secure channel id={}, bound channel: {}", channelId, channel);
            channel.close();
        }
    }

    public void secureChannelIssuedOrRenewed(ServerSecureChannel secureChannel, long lifetimeMillis) {
        long channelId = secureChannel.getChannelId();

        /*
         * Cancel any existing timeouts and start a new one.
         */
        Timeout timeout = timeouts.remove(channelId);
        boolean cancelled = (timeout == null || timeout.cancel());

        if (cancelled) {
            timeout = wheelTimer.newTimeout(t ->
                    closeSecureChannel(secureChannel), lifetimeMillis, TimeUnit.MILLISECONDS);

            timeouts.put(channelId, timeout);

            /*
             * If this is a reconnect there might be responses queued, so drain those.
             */
            Channel channel = secureChannel.attr(BoundChannelKey).get();

            if (channel != null) {
                List<ServiceResponse> responses = responseQueues.removeAll(channelId);

                responses.forEach(channel::write);
                channel.flush();
            }
        }
    }

    public ServerSecureChannel getSecureChannel(long channelId) {
        return secureChannels.get(channelId);
    }

    @SuppressWarnings("unchecked")
    public <T extends UaRequestMessage, U extends UaResponseMessage>
    void addRequestHandler(Class<T> requestClass, ServiceRequestHandler<T, U> requestHandler) {
        ServiceRequestHandler<UaRequestMessage, UaResponseMessage> handler =
                (ServiceRequestHandler<UaRequestMessage, UaResponseMessage>) requestHandler;

        handlers.put(requestClass, handler);
    }

    @Override
    public UaTcpStackServer addEndpoint(String endpointUri,
                                        String bindAddress,
                                        X509Certificate certificate,
                                        SecurityPolicy securityPolicy,
                                        MessageSecurityMode messageSecurity) {

        boolean invalidConfiguration = messageSecurity == MessageSecurityMode.Invalid ||
                (securityPolicy == SecurityPolicy.None && messageSecurity != MessageSecurityMode.None) ||
                (securityPolicy != SecurityPolicy.None && messageSecurity == MessageSecurityMode.None);

        if (invalidConfiguration) {
            logger.warn("Invalid configuration, ignoring: {} + {}", securityPolicy, messageSecurity);
        } else {
            try {
                URI uri = new URI(endpointUri);

                endpoints.add(new Endpoint(uri, bindAddress, certificate, securityPolicy, messageSecurity));
            } catch (URISyntaxException e) {
                logger.warn("Invalid endpoint URI, ignoring: {}", endpointUri);
            }
        }

        return this;
    }

    private EndpointDescription mapEndpoint(Endpoint endpoint) {
        return new EndpointDescription(
                endpoint.getEndpointUri().toString(),
                getApplicationDescription(),
                certificateByteString(endpoint.getCertificate()),
                endpoint.getMessageSecurity(),
                endpoint.getSecurityPolicy().getSecurityPolicyUri(),
                userTokenPolicies.toArray(new UserTokenPolicy[userTokenPolicies.size()]),
                Stack.UA_TCP_BINARY_TRANSPORT_URI,
                ubyte(endpoint.getSecurityLevel())
        );
    }

    private ByteString certificateByteString(Optional<X509Certificate> certificate) {
        if (certificate.isPresent()) {
            try {
                return ByteString.of(certificate.get().getEncoded());
            } catch (CertificateEncodingException e) {
                logger.error("Error decoding certificate.", e);
                return ByteString.NULL_VALUE;
            }
        } else {
            return ByteString.NULL_VALUE;
        }
    }

    private class DefaultDiscoveryServiceSet implements DiscoveryServiceSet {
        @Override
        public void onGetEndpoints(ServiceRequest<GetEndpointsRequest, GetEndpointsResponse> serviceRequest) {
            GetEndpointsRequest request = serviceRequest.getRequest();

            List<String> profileUris = request.getProfileUris() != null ?
                    Lists.newArrayList(request.getProfileUris()) :
                    Lists.newArrayList();

            List<EndpointDescription> eds = endpoints.stream()
                    .map(UaTcpStackServer.this::mapEndpoint)
                    .filter(ed -> filterProfileUris(ed, profileUris))
                    .filter(this::filterEndpointUrls)
                    .collect(Collectors.toList());

            GetEndpointsResponse response = new GetEndpointsResponse(
                    serviceRequest.createResponseHeader(),
                    eds.toArray(new EndpointDescription[eds.size()])
            );

            serviceRequest.setResponse(response);
        }

        private boolean filterProfileUris(EndpointDescription endpoint, List<String> profileUris) {
            return profileUris.size() == 0 || profileUris.contains(endpoint.getTransportProfileUri());
        }

        private boolean filterEndpointUrls(EndpointDescription endpoint) {
            return true;
        }

        @Override
        public void onFindServers(ServiceRequest<FindServersRequest, FindServersResponse> serviceRequest) {
            FindServersRequest request = serviceRequest.getRequest();

            List<String> serverUris = request.getServerUris() != null ?
                    Lists.newArrayList(request.getServerUris()) :
                    Lists.newArrayList();

            List<ApplicationDescription> applicationDescriptions = Lists.newArrayList(getApplicationDescription());

            applicationDescriptions = applicationDescriptions.stream()
                    .filter(ad -> filterServerUris(ad, serverUris))
                    .collect(Collectors.toList());

            FindServersResponse response = new FindServersResponse(
                    serviceRequest.createResponseHeader(),
                    applicationDescriptions.toArray(new ApplicationDescription[applicationDescriptions.size()])
            );

            serviceRequest.setResponse(response);
        }

        private boolean filterServerUris(ApplicationDescription ad, List<String> serverUris) {
            return serverUris.size() == 0 || serverUris.contains(ad.getApplicationUri());
        }

    }

}
