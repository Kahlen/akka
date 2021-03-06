/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.remote.protocol.RemoteProtocol.{ActorType => ActorTypeProtocol, _}
import se.scalablesolutions.akka.actor.{Exit, Actor, ActorRef, ActorType, RemoteActorRef, IllegalActorStateException}
import se.scalablesolutions.akka.dispatch.{DefaultCompletableFuture, CompletableFuture}
import se.scalablesolutions.akka.actor.{Uuid,newUuid,uuidFrom}
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.serialization.RemoteActorSerialization._
import se.scalablesolutions.akka.AkkaException
import Actor._
import org.jboss.netty.channel._
import group.DefaultChannelGroup
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.compression.{ZlibDecoder, ZlibEncoder}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}
import org.jboss.netty.handler.timeout.ReadTimeoutHandler
import org.jboss.netty.util.{TimerTask, Timeout, HashedWheelTimer}
import org.jboss.netty.handler.ssl.SslHandler

import java.net.{SocketAddress, InetSocketAddress}
import java.util.concurrent.{TimeUnit, Executors, ConcurrentMap, ConcurrentHashMap, ConcurrentSkipListSet}
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable.{HashSet, HashMap}
import scala.reflect.BeanProperty
import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.util.{Address, ListenerManagement, Logging, Duration}

/**
 * Life-cycle events for RemoteClient.
 */
sealed trait RemoteClientLifeCycleEvent
case class RemoteClientError(
  @BeanProperty val cause: Throwable,
  @BeanProperty val client: RemoteClient) extends RemoteClientLifeCycleEvent
case class RemoteClientDisconnected(
  @BeanProperty val client: RemoteClient) extends RemoteClientLifeCycleEvent
case class RemoteClientConnected(
  @BeanProperty val client: RemoteClient) extends RemoteClientLifeCycleEvent
case class RemoteClientStarted(
  @BeanProperty val client: RemoteClient) extends RemoteClientLifeCycleEvent
case class RemoteClientShutdown(
  @BeanProperty val client: RemoteClient) extends RemoteClientLifeCycleEvent

/**
 * Thrown for example when trying to send a message using a RemoteClient that is either not started or shut down.
 */
class RemoteClientException private[akka](message: String, @BeanProperty val client: RemoteClient) extends AkkaException(message)

/**
 * The RemoteClient object manages RemoteClient instances and gives you an API to lookup remote actor handles.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteClient extends Logging {
  val READ_TIMEOUT =    Duration(config.getInt("akka.remote.client.read-timeout", 1), TIME_UNIT)
  val RECONNECT_DELAY = Duration(config.getInt("akka.remote.client.reconnect-delay", 5), TIME_UNIT)

  private val remoteClients = new HashMap[String, RemoteClient]
  private val remoteActors =  new HashMap[Address, HashSet[Uuid]]

  def actorFor(classNameOrServiceId: String, hostname: String, port: Int): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, 5000L, hostname, port, None)

  def actorFor(classNameOrServiceId: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, 5000L, hostname, port, Some(loader))

  def actorFor(serviceId: String, className: String, hostname: String, port: Int): ActorRef =
    actorFor(serviceId, className, 5000L, hostname, port, None)

  def actorFor(serviceId: String, className: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(serviceId, className, 5000L, hostname, port, Some(loader))

  def actorFor(classNameOrServiceId: String, timeout: Long, hostname: String, port: Int): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, timeout, hostname, port, None)

  def actorFor(classNameOrServiceId: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, timeout, hostname, port, Some(loader))

  def actorFor(serviceId: String, className: String, timeout: Long, hostname: String, port: Int): ActorRef =
    RemoteActorRef(serviceId, className, hostname, port, timeout, None)

  def typedActorFor[T](intfClass: Class[T], serviceIdOrClassName: String, hostname: String, port: Int) : T = {
    typedActorFor(intfClass, serviceIdOrClassName, serviceIdOrClassName, 5000L, hostname, port, None)
  }

  def typedActorFor[T](intfClass: Class[T], serviceIdOrClassName: String, timeout: Long, hostname: String, port: Int) : T = {
    typedActorFor(intfClass, serviceIdOrClassName, serviceIdOrClassName, timeout, hostname, port, None)
  }

  def typedActorFor[T](intfClass: Class[T], serviceIdOrClassName: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader) : T = {
    typedActorFor(intfClass, serviceIdOrClassName, serviceIdOrClassName, timeout, hostname, port, Some(loader))
  }

  def typedActorFor[T](intfClass: Class[T], serviceId: String, implClassName: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader) : T = {
    typedActorFor(intfClass, serviceId, implClassName, timeout, hostname, port, Some(loader))
  }

  private[akka] def typedActorFor[T](intfClass: Class[T], serviceId: String, implClassName: String, timeout: Long, hostname: String, port: Int, loader: Option[ClassLoader]) : T = {
    val actorRef = RemoteActorRef(serviceId, implClassName, hostname, port, timeout, loader, ActorType.TypedActor)
    TypedActor.createProxyForRemoteActorRef(intfClass, actorRef)
  }

  private[akka] def actorFor(serviceId: String, className: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    RemoteActorRef(serviceId, className, hostname, port, timeout, Some(loader))

  private[akka] def actorFor(serviceId: String, className: String, timeout: Long, hostname: String, port: Int, loader: Option[ClassLoader]): ActorRef =
    RemoteActorRef(serviceId, className, hostname, port, timeout, loader)

  def clientFor(hostname: String, port: Int): RemoteClient =
    clientFor(new InetSocketAddress(hostname, port), None)

  def clientFor(hostname: String, port: Int, loader: ClassLoader): RemoteClient =
    clientFor(new InetSocketAddress(hostname, port), Some(loader))

  def clientFor(address: InetSocketAddress): RemoteClient =
    clientFor(address, None)

  def clientFor(address: InetSocketAddress, loader: ClassLoader): RemoteClient =
    clientFor(address, Some(loader))

  private[akka] def clientFor(hostname: String, port: Int, loader: Option[ClassLoader]): RemoteClient =
    clientFor(new InetSocketAddress(hostname, port), loader)

  private[akka] def clientFor(
    address: InetSocketAddress, loader: Option[ClassLoader]): RemoteClient = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ':' + port
    loader.foreach(MessageSerializer.setClassLoader(_))
    if (remoteClients.contains(hash)) remoteClients(hash)
    else {
      val client = new RemoteClient(hostname, port, loader)
      client.connect
      remoteClients += hash -> client
      client
    }
  }

  def shutdownClientFor(address: InetSocketAddress) = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ':' + port
    if (remoteClients.contains(hash)) {
      val client = remoteClients(hash)
      client.shutdown
      remoteClients -= hash
    }
  }

  /**
   * Clean-up all open connections.
   */
  def shutdownAll = synchronized {
    remoteClients.foreach({case (addr, client) => client.shutdown})
    remoteClients.clear
  }

  def register(hostname: String, port: Int, uuid: Uuid) = synchronized {
    actorsFor(Address(hostname, port)) += uuid
  }

  private[akka] def unregister(hostname: String, port: Int, uuid: Uuid) = synchronized {
    val set = actorsFor(Address(hostname, port))
    set -= uuid
    if (set.isEmpty) shutdownClientFor(new InetSocketAddress(hostname, port))
  }

  private[akka] def actorsFor(remoteServerAddress: Address): HashSet[Uuid] = {
    val set = remoteActors.get(remoteServerAddress)
    if (set.isDefined && (set.get ne null)) set.get
    else {
      val remoteActorSet = new HashSet[Uuid]
      remoteActors.put(remoteServerAddress, remoteActorSet)
      remoteActorSet
    }
  }
}

/**
 * RemoteClient represents a connection to a RemoteServer. Is used to send messages to remote actors on the RemoteServer.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClient private[akka] (
  val hostname: String, val port: Int, val loader: Option[ClassLoader] = None)
  extends Logging with ListenerManagement {
  val name = "RemoteClient@" + hostname + "::" + port

  //FIXME Should these be clear:ed on postStop?
  private val futures = new ConcurrentHashMap[Uuid, CompletableFuture[_]]
  private val supervisors = new ConcurrentHashMap[Uuid, ActorRef]

  private val remoteAddress = new InetSocketAddress(hostname, port)

  //FIXME rewrite to a wrapper object (minimize volatile access and maximize encapsulation)
  @volatile private[remote] var isRunning = false
  @volatile private var bootstrap: ClientBootstrap = _
  @volatile private[remote] var connection: ChannelFuture = _
  @volatile private[remote] var openChannels: DefaultChannelGroup = _
  @volatile private var timer: HashedWheelTimer = _

  private val reconnectionTimeWindow = Duration(config.getInt(
    "akka.remote.client.reconnection-time-window", 600), TIME_UNIT).toMillis
  @volatile private var reconnectionTimeWindowStart = 0L

  def connect = synchronized {
    if (!isRunning) {
      openChannels = new DefaultChannelGroup(classOf[RemoteClient].getName)
      timer = new HashedWheelTimer
      bootstrap = new ClientBootstrap(
        new NioClientSocketChannelFactory(
          Executors.newCachedThreadPool,Executors.newCachedThreadPool
        )
      )
      bootstrap.setPipelineFactory(new RemoteClientPipelineFactory(name, futures, supervisors, bootstrap, remoteAddress, timer, this))
      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("keepAlive", true)
      connection = bootstrap.connect(remoteAddress)
      log.info("Starting remote client connection to [%s:%s]", hostname, port)
      // Wait until the connection attempt succeeds or fails.
      val channel = connection.awaitUninterruptibly.getChannel
      openChannels.add(channel)
      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, this))
        log.error(connection.getCause, "Remote client connection to [%s:%s] has failed", hostname, port)
      }
      notifyListeners(RemoteClientStarted(this))
      isRunning = true
    }
  }

  def shutdown = synchronized {
    log.info("Shutting down %s", name)
    if (isRunning) {
      isRunning = false
      notifyListeners(RemoteClientShutdown(this))
      timer.stop
      timer = null
      openChannels.close.awaitUninterruptibly
      openChannels = null
      bootstrap.releaseExternalResources
      bootstrap = null
      connection = null
      log.info("%s has been shut down", name)
    }
  }

  @deprecated("Use addListener instead")
  def registerListener(actorRef: ActorRef) = addListener(actorRef)

  @deprecated("Use removeListener instead")
  def deregisterListener(actorRef: ActorRef) = removeListener(actorRef)

  override def notifyListeners(message: => Any): Unit = super.notifyListeners(message)

  protected override def manageLifeCycleOfListeners = false

  def send[T](
    message: Any,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]],
    remoteAddress: InetSocketAddress,
    timeout: Long,
    isOneWay: Boolean,
    actorRef: ActorRef,
    typedActorInfo: Option[Tuple2[String, String]],
    actorType: ActorType): Option[CompletableFuture[T]] = {
    send(createRemoteRequestProtocolBuilder(
      actorRef, message, isOneWay, senderOption, typedActorInfo, actorType).build, senderFuture)
 }

  def send[T](
    request: RemoteRequestProtocol,
    senderFuture: Option[CompletableFuture[T]]):
    Option[CompletableFuture[T]] = if (isRunning) {
    if (request.getIsOneWay) {
      connection.getChannel.write(request)
      None
    } else {
      futures.synchronized {
        val futureResult = if (senderFuture.isDefined) senderFuture.get
        else new DefaultCompletableFuture[T](request.getActorInfo.getTimeout)
        futures.put(uuidFrom(request.getUuid.getHigh,request.getUuid.getLow), futureResult)
        connection.getChannel.write(request)
        Some(futureResult)
      }
    }
  } else {
    val exception = new RemoteClientException(
      "Remote client is not running, make sure you have invoked 'RemoteClient.connect' before using it.", this)
    notifyListeners(RemoteClientError(exception, this))
    throw exception
  }

  private[akka] def registerSupervisorForActor(actorRef: ActorRef) =
    if (!actorRef.supervisor.isDefined) throw new IllegalActorStateException(
      "Can't register supervisor for " + actorRef + " since it is not under supervision")
    else supervisors.putIfAbsent(actorRef.supervisor.get.uuid, actorRef)

  private[akka] def deregisterSupervisorForActor(actorRef: ActorRef) =
    if (!actorRef.supervisor.isDefined) throw new IllegalActorStateException(
      "Can't unregister supervisor for " + actorRef + " since it is not under supervision")
    else supervisors.remove(actorRef.supervisor.get.uuid)

  private[akka] def isWithinReconnectionTimeWindow: Boolean = {
    if (reconnectionTimeWindowStart == 0L) {
      reconnectionTimeWindowStart = System.currentTimeMillis
      true
    } else {
      val timeLeft = reconnectionTimeWindow - (System.currentTimeMillis - reconnectionTimeWindowStart)
      if (timeLeft > 0) {
        log.info("Will try to reconnect to remote server for another [%s] milliseconds", timeLeft)
        true
      } else false
    }
  }

  private[akka] def resetReconnectionTimeWindow = reconnectionTimeWindowStart = 0L
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClientPipelineFactory(
    name: String,
    futures: ConcurrentMap[Uuid, CompletableFuture[_]],
    supervisors: ConcurrentMap[Uuid, ActorRef],
    bootstrap: ClientBootstrap,
    remoteAddress: SocketAddress,
    timer: HashedWheelTimer,
    client: RemoteClient) extends ChannelPipelineFactory {

  def getPipeline: ChannelPipeline = {
    def join(ch: ChannelHandler*) = Array[ChannelHandler](ch: _*)

    lazy val engine = {
      val e = RemoteServerSslContext.client.createSSLEngine()
      e.setEnabledCipherSuites(e.getSupportedCipherSuites) //TODO is this sensible?
      e.setUseClientMode(true)
      e
    }

    val ssl         = if (RemoteServer.SECURE) join(new SslHandler(engine)) else join()
    val timeout     = new ReadTimeoutHandler(timer, RemoteClient.READ_TIMEOUT.toMillis.toInt)
    val lenDec      = new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
    val lenPrep     = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(RemoteReplyProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec)  = RemoteServer.COMPRESSION_SCHEME match {
      case "zlib" => (join(new ZlibEncoder(RemoteServer.ZLIB_COMPRESSION_LEVEL)), join(new ZlibDecoder))
      case _      => (join(), join())
    }

    val remoteClient = new RemoteClientHandler(name, futures, supervisors, bootstrap, remoteAddress, timer, client)
    val stages = ssl ++ join(timeout) ++ dec ++ join(lenDec, protobufDec) ++ enc ++ join(lenPrep, protobufEnc, remoteClient)
    new StaticChannelPipeline(stages: _*)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelHandler.Sharable
class RemoteClientHandler(
    val name: String,
    val futures: ConcurrentMap[Uuid, CompletableFuture[_]],
    val supervisors: ConcurrentMap[Uuid, ActorRef],
    val bootstrap: ClientBootstrap,
    val remoteAddress: SocketAddress,
    val timer: HashedWheelTimer,
    val client: RemoteClient)
    extends SimpleChannelUpstreamHandler with Logging {

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] &&
        event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      val result = event.getMessage
      if (result.isInstanceOf[RemoteReplyProtocol]) {
        val reply = result.asInstanceOf[RemoteReplyProtocol]
        val replyUuid = uuidFrom(reply.getUuid.getHigh,reply.getUuid.getLow)
        log.debug("Remote client received RemoteReplyProtocol[\n%s]", reply.toString)
        val future = futures.get(replyUuid).asInstanceOf[CompletableFuture[Any]]
        if (reply.getIsSuccessful) {
          val message = MessageSerializer.deserialize(reply.getMessage)
          future.completeWithResult(message)
        } else {
          if (reply.hasSupervisorUuid()) {
            val supervisorUuid = uuidFrom(reply.getSupervisorUuid.getHigh,reply.getSupervisorUuid.getLow)
            if (!supervisors.containsKey(supervisorUuid)) throw new IllegalActorStateException(
              "Expected a registered supervisor for UUID [" + supervisorUuid + "] but none was found")
            val supervisedActor = supervisors.get(supervisorUuid)
            if (!supervisedActor.supervisor.isDefined) throw new IllegalActorStateException(
              "Can't handle restart for remote actor " + supervisedActor + " since its supervisor has been removed")
            else supervisedActor.supervisor.get ! Exit(supervisedActor, parseException(reply, client.loader))
          }
          future.completeWithException(parseException(reply, client.loader))
        }
        futures remove replyUuid
      } else {
        val exception = new RemoteClientException("Unknown message received in remote client handler: " + result, client)
        client.notifyListeners(RemoteClientError(exception, client))
        throw exception
      }
    } catch {
      case e: Exception =>
        client.notifyListeners(RemoteClientError(e, client))
        log.error("Unexpected exception in remote client handler: %s", e)
        throw e
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = if (client.isRunning) {
    if (client.isWithinReconnectionTimeWindow) {
      timer.newTimeout(new TimerTask() {
        def run(timeout: Timeout) = {
          client.openChannels.remove(event.getChannel)
          log.debug("Remote client reconnecting to [%s]", remoteAddress)
          client.connection = bootstrap.connect(remoteAddress)
          client.connection.awaitUninterruptibly // Wait until the connection attempt succeeds or fails.
          if (!client.connection.isSuccess) {
            client.notifyListeners(RemoteClientError(client.connection.getCause, client))
            log.error(client.connection.getCause, "Reconnection to [%s] has failed", remoteAddress)
          }
        }
      }, RemoteClient.RECONNECT_DELAY.toMillis, TimeUnit.MILLISECONDS)
    } else spawn { client.shutdown }
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    def connect = {
      client.notifyListeners(RemoteClientConnected(client))
      log.debug("Remote client connected to [%s]", ctx.getChannel.getRemoteAddress)
      client.resetReconnectionTimeWindow
    }

    if (RemoteServer.SECURE) {
      val sslHandler: SslHandler = ctx.getPipeline.get(classOf[SslHandler])
      sslHandler.handshake.addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture): Unit = {
          if (future.isSuccess) connect
          else throw new RemoteClientException("Could not establish SSL handshake", client)
        }
      })
    } else connect
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientDisconnected(client))
    log.debug("Remote client disconnected from [%s]", ctx.getChannel.getRemoteAddress)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    client.notifyListeners(RemoteClientError(event.getCause, client))
    log.error(event.getCause, "Unexpected exception from downstream in remote client")
    event.getChannel.close
  }

  private def parseException(reply: RemoteReplyProtocol, loader: Option[ClassLoader]): Throwable = {
    val exception = reply.getException
    val classname = exception.getClassname
    val exceptionClass = if (loader.isDefined) loader.get.loadClass(classname)
                         else Class.forName(classname)
    exceptionClass
        .getConstructor(Array[Class[_]](classOf[String]): _*)
        .newInstance(exception.getMessage).asInstanceOf[Throwable]
  }
}
