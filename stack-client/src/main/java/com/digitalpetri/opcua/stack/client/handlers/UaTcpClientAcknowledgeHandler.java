package com.digitalpetri.opcua.stack.client.handlers;

import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.digitalpetri.opcua.stack.client.UaTcpStackClient;
import com.digitalpetri.opcua.stack.client.UaTcpStackClient.UaTcpClientHandler;
import com.digitalpetri.opcua.stack.core.StatusCodes;
import com.digitalpetri.opcua.stack.core.UaException;
import com.digitalpetri.opcua.stack.core.channel.ChannelConfig;
import com.digitalpetri.opcua.stack.core.channel.ChannelParameters;
import com.digitalpetri.opcua.stack.core.channel.SerializationQueue;
import com.digitalpetri.opcua.stack.core.channel.headers.HeaderDecoder;
import com.digitalpetri.opcua.stack.core.channel.messages.AcknowledgeMessage;
import com.digitalpetri.opcua.stack.core.channel.messages.ErrorMessage;
import com.digitalpetri.opcua.stack.core.channel.messages.HelloMessage;
import com.digitalpetri.opcua.stack.core.channel.messages.MessageType;
import com.digitalpetri.opcua.stack.core.channel.messages.TcpMessageDecoder;
import com.digitalpetri.opcua.stack.core.channel.messages.TcpMessageEncoder;
import com.digitalpetri.opcua.stack.core.serialization.UaMessage;
import com.digitalpetri.opcua.stack.core.types.builtin.StatusCode;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UaTcpClientAcknowledgeHandler extends ByteToMessageCodec<UaMessage> implements HeaderDecoder {

    public static final AttributeKey<List<UaMessage>> KEY_AWAITING_HANDSHAKE =
            AttributeKey.valueOf("awaiting-handshake");

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final List<UaMessage> awaitingHandshake = Lists.newArrayList();

    private final CompletableFuture<Channel> handshakeFuture;

    private final UaTcpStackClient client;

    public UaTcpClientAcknowledgeHandler(UaTcpStackClient client, CompletableFuture<Channel> handshakeFuture) {
        this.client = client;
        this.handshakeFuture = handshakeFuture;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        HelloMessage hello = new HelloMessage(
                PROTOCOL_VERSION,
                client.getChannelConfig().getMaxChunkSize(),
                client.getChannelConfig().getMaxChunkSize(),
                client.getChannelConfig().getMaxMessageSize(),
                client.getChannelConfig().getMaxChunkCount(),
                client.getEndpointUrl());

        ByteBuf messageBuffer = TcpMessageEncoder.encode(hello);

        ctx.writeAndFlush(messageBuffer);

        logger.debug("Sent Hello message on channel={}.", ctx.channel());

        super.channelActive(ctx);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, UaMessage message, ByteBuf out) throws Exception {
        awaitingHandshake.add(message);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.readableBytes() >= HeaderLength &&
                buffer.readableBytes() >= getMessageLength(buffer)) {

            int messageLength = getMessageLength(buffer);
            MessageType messageType = MessageType.fromMediumInt(buffer.getMedium(buffer.readerIndex()));

            switch (messageType) {
                case Acknowledge:
                    onAcknowledge(ctx, buffer.readSlice(messageLength));
                    break;

                case Error:
                    onError(ctx, buffer.readSlice(messageLength));
                    break;

                default:
                    out.add(buffer.readSlice(messageLength).retain());
            }
        }
    }

    private void onAcknowledge(ChannelHandlerContext ctx, ByteBuf buffer) {
        logger.debug("Received Acknowledge message on channel={}.", ctx.channel());

        buffer.skipBytes(3 + 1 + 4); // Skip messageType, chunkType, and messageSize

        AcknowledgeMessage acknowledge = AcknowledgeMessage.decode(buffer);

        long remoteProtocolVersion = acknowledge.getProtocolVersion();
        long remoteReceiveBufferSize = acknowledge.getReceiveBufferSize();
        long remoteSendBufferSize = acknowledge.getSendBufferSize();
        long remoteMaxMessageSize = acknowledge.getMaxMessageSize();
        long remoteMaxChunkCount = acknowledge.getMaxChunkCount();

        if (PROTOCOL_VERSION > remoteProtocolVersion) {
            logger.warn("Client protocol version ({}) does not match server protocol version ({}).",
                    PROTOCOL_VERSION, remoteProtocolVersion);
        }

        ChannelConfig config = client.getChannelConfig();

        /* Our receive buffer size is determined by the remote send buffer size. */
        long localReceiveBufferSize = Math.min(remoteSendBufferSize, config.getMaxChunkSize());

        /* Our send buffer size is determined by the remote receive buffer size. */
        long localSendBufferSize = Math.min(remoteReceiveBufferSize, config.getMaxChunkSize());

        /* Max message size the remote can send us; not influenced by remote configuration. */
        long localMaxMessageSize = config.getMaxMessageSize();

        /* Max chunk count the remote can send us; not influenced by remote configuration. */
        long localMaxChunkCount = config.getMaxChunkCount();

        ChannelParameters parameters = new ChannelParameters(
                Ints.saturatedCast(localMaxMessageSize),
                Ints.saturatedCast(localReceiveBufferSize),
                Ints.saturatedCast(localSendBufferSize),
                Ints.saturatedCast(localMaxChunkCount),
                Ints.saturatedCast(remoteMaxMessageSize),
                Ints.saturatedCast(remoteReceiveBufferSize),
                Ints.saturatedCast(remoteSendBufferSize),
                Ints.saturatedCast(remoteMaxChunkCount)
        );

        ctx.channel().attr(KEY_AWAITING_HANDSHAKE).set(awaitingHandshake);

        ctx.executor().execute(() -> {
            int maxArrayLength = client.getChannelConfig().getMaxArrayLength();
            int maxStringLength = client.getChannelConfig().getMaxStringLength();

            UaTcpClientAsymmetricHandler handler = new UaTcpClientAsymmetricHandler(
                    client,
                    new SerializationQueue(parameters, maxArrayLength, maxStringLength),
                    handshakeFuture);

            ctx.pipeline().addLast(handler);
        });
    }

    private void onError(ChannelHandlerContext ctx, ByteBuf buffer) {
        try {
            ErrorMessage errorMessage = TcpMessageDecoder.decodeError(buffer);
            StatusCode errorCode = errorMessage.getError();

            boolean secureChannelError =
                    errorCode.getValue() == StatusCodes.Bad_TcpSecureChannelUnknown ||
                            errorCode.getValue() == StatusCodes.Bad_SecureChannelIdInvalid;

            if (secureChannelError) {
                client.getSecureChannel().setChannelId(0);
            }

            logger.error("Received error message: " + errorMessage);

            if (ctx.pipeline().context(UaTcpClientHandler.class) != null) {
                UaTcpClientHandler handler = ctx.pipeline().remove(UaTcpClientHandler.class);
                if (secureChannelError) {
                    handler.secureChannelError(ctx, errorCode);
                }
            }

            handshakeFuture.completeExceptionally(new UaException(errorCode, "error=" + errorMessage.getReason()));
        } catch (UaException e) {
            logger.error("An exception occurred while decoding an error message: {}", e.getMessage(), e);
            if (ctx.pipeline().context(UaTcpClientHandler.class) != null) {
                ctx.pipeline().remove(UaTcpClientHandler.class);
            }
            handshakeFuture.completeExceptionally(e);
        } finally {
            ctx.close();
        }
    }

}
