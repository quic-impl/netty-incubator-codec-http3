/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.http3;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;

import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Handler that handles <a href="https://tools.ietf.org/html/draft-ietf-quic-http-32">HTTP3</a> connections.
 */
public abstract class Http3ConnectionHandler extends ChannelInboundHandlerAdapter {
    private final Supplier<Http3FrameCodec> codecSupplier;
    private final boolean server;
    private final Http3SettingsFrame localSettings;
    private final ChannelHandler inboundControlStreamHandler;
    private final LongFunction<ChannelHandler> unknownInboundStreamHandlerFactory;
    private Http3ControlStreamFrameDispatcher controlStreamFrameDispatcher;

    /**
     * Create a new instance.
     * @parma server                                {@code true} if server-side, {@code false} otherwise.
     * @param inboundControlStreamHandler           the {@link ChannelHandler} which will be notified about
     *                                              {@link Http3RequestStreamFrame}s or {@code null} if the user is not
     *                                              interested in these.
     * @param unknownInboundStreamHandlerFactory    the {@link LongFunction} that will provide a custom
     *                                              {@link ChannelHandler} for unknown inbound stream types or
     *                                              {@code null} if no special handling should be done.
     * @param localSettings                         the local {@link Http3SettingsFrame} that should be sent to the
     *                                              remote peer or {@code null} if the default settings should be used.
     */
    Http3ConnectionHandler(boolean server, ChannelHandler inboundControlStreamHandler,
                           LongFunction<ChannelHandler> unknownInboundStreamHandlerFactory,
                           Http3SettingsFrame localSettings) {
        this.server = server;
        this.inboundControlStreamHandler = inboundControlStreamHandler;
        this.unknownInboundStreamHandlerFactory = unknownInboundStreamHandlerFactory;
        if (localSettings == null) {
            localSettings = new DefaultHttp3SettingsFrame();
        } else {
            localSettings = DefaultHttp3SettingsFrame.copyOf(localSettings);
        }
        Long maxFieldSectionSize = localSettings.get(Http3SettingsFrame.HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE);
        if (maxFieldSectionSize == null) {
            maxFieldSectionSize = Http3CodecUtils.DEFAULT_MAX_HEADER_LIST_SIZE;
            localSettings.put(Http3SettingsFrame.HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE, maxFieldSectionSize);
        }
        // As we not support the dynamic table at the moment lets override whatever the user specified and set
        // the capacity to 0.
        localSettings.put(Http3SettingsFrame.HTTP3_SETTINGS_QPACK_MAX_TABLE_CAPACITY, 0L);
        this.localSettings = localSettings;
        codecSupplier = Http3FrameCodec.newSupplier(new QpackDecoder(), maxFieldSectionSize, new QpackEncoder());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        QuicChannel channel = (QuicChannel) ctx.channel();
        // Once the channel became active we need to create an unidirectional stream and write the Http3SettingsFrame
        // to it. This needs to be the first frame on this stream.
        // https://tools.ietf.org/html/draft-ietf-quic-http-32#section-6.2.1.
        channel.createStream(QuicStreamType.UNIDIRECTIONAL,
                new Http3ControlStreamOutboundHandler(localSettings, codecSupplier))
                .addListener(f -> {
            if (!f.isSuccess()) {
                // TODO: Handle me the right way.
                ctx.close();
            } else {
                controlStreamFrameDispatcher = new Http3ControlStreamFrameDispatcher((QuicStreamChannel) f.get());
            }
        });

        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        QuicStreamChannel channel = (QuicStreamChannel) msg;
        switch (channel.type()) {
            case BIDIRECTIONAL:
                initBidirectionalStream(channel, codecSupplier, controlStreamFrameDispatcher);
                break;
            case UNIDIRECTIONAL:
                channel.pipeline().addLast(
                        new Http3UnidirectionalStreamInboundHandler(server, codecSupplier,
                                inboundControlStreamHandler, unknownInboundStreamHandlerFactory));
                break;
            default:
                throw new Error();
        }
        ctx.fireChannelRead(msg);
    }

    abstract void initBidirectionalStream(QuicStreamChannel channel, Supplier<Http3FrameCodec> codecSupplier,
                                          Http3ControlStreamFrameDispatcher dispatcher);

    /**
     * Always returns {@code false} as it keeps state.
     */
    @Override
    public boolean isSharable() {
        return false;
    }
}