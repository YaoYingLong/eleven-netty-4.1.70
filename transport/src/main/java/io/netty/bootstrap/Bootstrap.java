/*
 * Copyright 2012 The Netty Project
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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NameResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * A {@link Bootstrap} that makes it easy to bootstrap a {@link Channel} to use
 * for clients.
 *
 * <p>The {@link #bind()} methods are useful in combination with connectionless transports such as datagram (UDP).
 * For regular TCP connections, please use the provided {@link #connect()} methods.</p>
 */
public class Bootstrap extends AbstractBootstrap<Bootstrap, Channel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Bootstrap.class);

    private static final AddressResolverGroup<?> DEFAULT_RESOLVER = DefaultAddressResolverGroup.INSTANCE;

    private final BootstrapConfig config = new BootstrapConfig(this);

    @SuppressWarnings("unchecked")
    private volatile AddressResolverGroup<SocketAddress> resolver = (AddressResolverGroup<SocketAddress>) DEFAULT_RESOLVER;
    private volatile SocketAddress remoteAddress;

    public Bootstrap() { }

    private Bootstrap(Bootstrap bootstrap) {
        super(bootstrap);
        resolver = bootstrap.resolver;
        remoteAddress = bootstrap.remoteAddress;
    }

    /**
     * Sets the {@link NameResolver} which will resolve the address of the unresolved named address.
     *
     * @param resolver the {@link NameResolver} for this {@code Bootstrap}; may be {@code null}, in which case a default
     *                 resolver will be used
     *
     * @see io.netty.resolver.DefaultAddressResolverGroup
     */
    @SuppressWarnings("unchecked")
    public Bootstrap resolver(AddressResolverGroup<?> resolver) {
        this.resolver = (AddressResolverGroup<SocketAddress>) (resolver == null ? DEFAULT_RESOLVER : resolver);
        return this;
    }

    /**
     * The {@link SocketAddress} to connect to once the {@link #connect()} method
     * is called.
     */
    public Bootstrap remoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(String inetHost, int inetPort) {
        remoteAddress = InetSocketAddress.createUnresolved(inetHost, inetPort);
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(InetAddress inetHost, int inetPort) {
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect() {
        validate();
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            throw new IllegalStateException("remoteAddress not set");
        }

        return doResolveAndConnect(remoteAddress, config.localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     *
     * 例子：bootstrap.connect("127.0.0.1", 9000)
     */
    public ChannelFuture connect(String inetHost, int inetPort) {
        return connect(InetSocketAddress.createUnresolved(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(InetAddress inetHost, int inetPort) {
        return connect(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(SocketAddress remoteAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate();
        return doResolveAndConnect(remoteAddress, config.localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate();
        return doResolveAndConnect(remoteAddress, localAddress);
    }

    /**
     * @see #connect()
     */
    private ChannelFuture doResolveAndConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
        /**
         * 1、通过调用ReflectiveChannelFactory的newChannel方法，通过反射的方式调用NioSocketChannel的构造方法实例化Channel
         *  - 调用SelectorProvider的openSocketChannel方法生成SocketChannel，并持有该SocketChannel
         *  - 会调用其超类的构造方法初始化ChannelPipeline
         *  - 将SocketChannel感兴趣的事件设置为OP_READ
         *  - 通过SocketChannel.configureBlocking(false)将SocketChannel设置为非阻塞模式
         *
         * 2、给NioSocketChannel设置options参数
         * 3、将用户自定义调用Bootstrap的handler方法添加进来的ChannelInitializer添加到ChannelPipeline
         * 4、调用MultithreadEventLoopGroup的register方法，将NioSocketChannel注册到BossGroup中的一个NioEventLoop线程的Selector上
         */
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();

        if (regFuture.isDone()) {
            if (!regFuture.isSuccess()) {
                return regFuture;
            }
            return doResolveAndConnect0(channel, remoteAddress, localAddress, channel.newPromise());
        } else {
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    // Directly obtain the cause and do a null check so we only need one volatile read in case of a
                    // failure.
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.registered();
                        // 这里会真正调用SocketChannel的connect方法，连接到服务端
                        doResolveAndConnect0(channel, remoteAddress, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    private ChannelFuture doResolveAndConnect0(final Channel channel, SocketAddress remoteAddress,
                                               final SocketAddress localAddress, final ChannelPromise promise) {
        try {
            final EventLoop eventLoop = channel.eventLoop();
            AddressResolver<SocketAddress> resolver;
            try {
                // AddressResolverGroup<SocketAddress>即resolver默认为DefaultAddressResolverGroup
                resolver = this.resolver.getResolver(eventLoop);
            } catch (Throwable cause) {
                channel.close();
                return promise.setFailure(cause);
            }

            if (!resolver.isSupported(remoteAddress) || resolver.isResolved(remoteAddress)) {
                // Resolver has no idea about what to do with the specified remote address or it's resolved already.
                doConnect(remoteAddress, localAddress, promise);
                return promise;
            }

            final Future<SocketAddress> resolveFuture = resolver.resolve(remoteAddress);

            if (resolveFuture.isDone()) {
                final Throwable resolveFailureCause = resolveFuture.cause();

                if (resolveFailureCause != null) {
                    // Failed to resolve immediately
                    channel.close();
                    promise.setFailure(resolveFailureCause);
                } else {
                    // Succeeded to resolve immediately; cached? (or did a blocking lookup)
                    doConnect(resolveFuture.getNow(), localAddress, promise);
                }
                return promise;
            }

            // Wait until the name resolution is finished.
            resolveFuture.addListener(new FutureListener<SocketAddress>() {
                @Override
                public void operationComplete(Future<SocketAddress> future) throws Exception {
                    if (future.cause() != null) {
                        channel.close();
                        promise.setFailure(future.cause());
                    } else {
                        doConnect(future.getNow(), localAddress, promise);
                    }
                }
            });
        } catch (Throwable cause) {
            promise.tryFailure(cause);
        }
        return promise;
    }

    private static void doConnect(final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise connectPromise) {
        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        final Channel channel = connectPromise.channel();
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (localAddress == null) {
                    channel.connect(remoteAddress, connectPromise);
                } else {
                    channel.connect(remoteAddress, localAddress, connectPromise);
                }
                connectPromise.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        });
    }

    @Override
    void init(Channel channel) {
        // 获取通过NioSocketChannel构造方法创建的ChannelPipeline
        ChannelPipeline p = channel.pipeline();
        // config.handler()获取到的是调用Bootstrap的handler方法添加进来的ChannelInitializer
        p.addLast(config.handler());
        // NioSocketChannel设置options参数
        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());
    }

    @Override
    public Bootstrap validate() {
        super.validate();
        if (config.handler() == null) {
            throw new IllegalStateException("handler not set");
        }
        return this;
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public Bootstrap clone() {
        return new Bootstrap(this);
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration except that it uses
     * the given {@link EventLoopGroup}. This method is useful when making multiple {@link Channel}s with similar
     * settings.
     */
    public Bootstrap clone(EventLoopGroup group) {
        Bootstrap bs = new Bootstrap(this);
        bs.group = group;
        return bs;
    }

    @Override
    public final BootstrapConfig config() {
        return config;
    }

    final SocketAddress remoteAddress() {
        return remoteAddress;
    }

    final AddressResolverGroup<?> resolver() {
        return resolver;
    }
}
