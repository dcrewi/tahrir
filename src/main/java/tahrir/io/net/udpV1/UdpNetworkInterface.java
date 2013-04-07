package tahrir.io.net.udpV1;

import com.google.common.base.Function;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tahrir.TrConstants;
import tahrir.io.net.PhysicalNetworkLocation;
import tahrir.io.net.TrNetworkInterface;
import tahrir.io.net.TrRemoteConnection;
import tahrir.tools.ByteArraySegment;
import tahrir.tools.TrUtils;
import tahrir.tools.Tuple2;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author Ian Clarke <ian.clarke@gmail.com>
 *
 */
public class UdpNetworkInterface extends TrNetworkInterface {
	private final org.slf4j.Logger logger;
	public final RSAPrivateKey myPrivateKey;
	public final RSAPublicKey myPublicKey;
	public Map<PhysicalNetworkLocation, UdpRemoteConnection> remoteConnections = Maps.newConcurrentMap();
	private final DatagramSocket datagramSocket;

	private final PriorityBlockingQueue<QueuedPacket> outbox = new PriorityBlockingQueue<UdpNetworkInterface.QueuedPacket>();

	private final Receiver receiver;

	private final Sender sender;

	private double simPercentageLoss = 0;

	final UNIConfig config;

	public UdpNetworkInterface(final UNIConfig config, final Tuple2<RSAPublicKey, RSAPrivateKey> keyPair)
			throws SocketException {
		this.config = config;
		myPublicKey = keyPair.a;
		myPrivateKey = keyPair.b;

		logger = LoggerFactory.getLogger(UdpNetworkInterface.class.getName()+" ("+config.listenPort+")");
		datagramSocket = new DatagramSocket(config.listenPort);
		datagramSocket.setSoTimeout(500);
		sender = new Sender(this);
		sender.start();
		receiver = new Receiver(this);
		receiver.start();
	}

	/**
	 * @param remoteAddress
	 * @param remotePubKey
	 * @param listener Listener for receiving inbound messages on this connection
	 * @param connectedCallback Callback informing us whether the connection was successful
	 * @param disconnectedCallback Callback for when this connection is broken
	 * @param unilateral Is the other node trying to connect back to us?
	 */
	@Override
	public TrRemoteConnection connect(final PhysicalNetworkLocation remoteAddress,
									  final RSAPublicKey remotePubKey,
									  final tahrir.io.net.TrNetworkInterface.TrMessageListener listener,
									  final Function<TrRemoteConnection, Void> connectedCallback,
									  final Runnable disconnectedCallback, final boolean unilateral) {
		// Should they need to pass in all the additional info if we already
		// have a connection, since it isn't used?
		UdpRemoteConnection conn = remoteConnections.get(remoteAddress);
		if (conn != null)
			return conn;

		conn = new UdpRemoteConnection(this, (UdpNetworkLocation) remoteAddress, remotePubKey, listener,
				connectedCallback,
				disconnectedCallback, unilateral);
		remoteConnections.put(remoteAddress, conn);
		return conn;
	}

	@Override
	public void sendTo(final PhysicalNetworkLocation recepient, final ByteArraySegment message, final double priority) {
		// We redeclare this to make it visible in this package
		super.sendTo(recepient, message, priority);
	}

	@Override
	protected void sendTo(final PhysicalNetworkLocation recepient_, final ByteArraySegment encryptedMessage,
						  final tahrir.io.net.TrNetworkInterface.TrSentListener sentListener, final double priority) {
		final UdpNetworkLocation recepient = (UdpNetworkLocation) recepient_;
		assert encryptedMessage.length <= TrConstants.MAX_UDP_PACKET_SIZE : "Packet length " + encryptedMessage.length
				+ " greater than " + TrConstants.MAX_UDP_PACKET_SIZE;
		final QueuedPacket qp = new QueuedPacket(recepient, encryptedMessage, sentListener, priority);
		outbox.add(qp);
	}

	@Override
	public void shutdown() {
		sender.active = false;
		sender.interrupt();
		receiver.active = false;
	}


	@Override
	public String toString() {
		return "UDP<" + datagramSocket.getLocalPort() + ">";
	}

	@Override
	protected Class<? extends PhysicalNetworkLocation> getAddressClass() {
		return UdpNetworkLocation.class;
	}

	protected void setSimPercentageLoss(final Double percentage) {
		simPercentageLoss = percentage;
	}

	/**
	 * Config for unicast transmission.
	 */
	public static class UNIConfig {
		public int listenPort = TrUtils.rand.nextInt(10000)+10000;

		public volatile int maxUpstreamBytesPerSecond = 1024;
	}

	private static class QueuedPacket implements Comparable<QueuedPacket> {

		private final UdpNetworkLocation addr;
		private final ByteArraySegment data;
		private final double priority;
		private final tahrir.io.net.TrNetworkInterface.TrSentListener sentListener;

		public QueuedPacket(final UdpNetworkLocation addr, final ByteArraySegment encryptedMessage,
							final tahrir.io.net.TrNetworkInterface.TrSentListener sentListener, final double priority) {
			this.addr = addr;
			data = encryptedMessage;
			this.sentListener = sentListener;
			this.priority = priority;

		}

		public int compareTo(final QueuedPacket other) {
			return Double.compare(priority, other.priority);
		}

	}

	private static class Receiver extends Thread {
		public volatile boolean active = true;

		private final UdpNetworkInterface parent;

		private final Logger logger;

		public Receiver(final UdpNetworkInterface parent) {
			logger = parent.logger;
			this.parent = parent;
		}

		@Override
		public void run() {

			while (active) {
				final DatagramPacket dp = new DatagramPacket(new byte[TrConstants.MAX_UDP_PACKET_SIZE ],
						TrConstants.MAX_UDP_PACKET_SIZE );
				try {
					parent.datagramSocket.receive(dp);

					if (isPacketToDrop()) {
						logger.debug("Dropping packet");
						continue;
					}

					final UdpNetworkLocation ura = new UdpNetworkLocation(dp.getAddress(), dp.getPort());
					UdpRemoteConnection connection = parent.remoteConnections.get(ura);
					logger.debug("Retrieving "+ura+" (hash:"+ura.hashCode()+" from "+parent.remoteConnections+" => "+connection);

					if (connection != null) {
						// We have a connection to the sender, forward this message to it
						try {
							connection.received(parent, ura, ByteArraySegment.from(dp));
						} catch (final Exception e) {
							parent.logger.error(
									"Error handling received UDP packet on port "
											+ parent.datagramSocket.getLocalPort() + " from port " + dp.getPort(), e);
						}
					} else {
						if (parent.newConnectionListener == null) {
							logger.debug("Ignoring unilateral message from "+ura+" as interface does not allow unilateral inbound");
						} else {
							logger.debug("Received unilateral message from "+ura+" creating connection to handle it");
							connection = new UdpRemoteConnection(parent, ura, null, parent.newConnectionListener, new Function<TrRemoteConnection, Void>() {

								@Override
								public Void apply(final TrRemoteConnection input) {
									// TODO Auto-generated method stub
									return null;
								}}, new Runnable() {

								@Override
								public void run() {
									logger.debug("Ulilateral inbound connection from "+ura+" has disconnected, removing");
									parent.remoteConnections.remove(ura);
								}}, false);
							parent.remoteConnections.put(ura, connection);
							connection.received(parent, ura, ByteArraySegment.from(dp));
						}
					}
				} catch (final SocketTimeoutException e) {
					// NOOP
				} catch (final IOException e) {
					parent.logger.error("Error receiving udp packet on port " + parent.datagramSocket.getLocalPort()
							+ ", receiveractive=" + active, e);
				}
			}
			parent.datagramSocket.close();
		}

		private boolean isPacketToDrop() {
			return parent.simPercentageLoss > 0 && Math.random() <= parent.simPercentageLoss;
		}
	}

	private static class Sender extends Thread {
		public volatile boolean active = true;
		private final UdpNetworkInterface parent;
		private final Logger logger;

		public Sender(final UdpNetworkInterface parent) {
			this.parent = parent;
			logger = parent.logger;
		}

		@Override
		public void run() {
			while (active) {
				try {
					final long startTime = System.currentTimeMillis();
					final QueuedPacket packet = parent.outbox.poll(1, TimeUnit.SECONDS);

					if (packet != null) {
						final DatagramPacket dp = new DatagramPacket(packet.data.array, packet.data.offset,
								packet.data.length,
								packet.addr.inetAddress, packet.addr.port);
						try {
							parent.datagramSocket.send(dp);
							if (packet.sentListener != null) {
								packet.sentListener.sent();
							}
						} catch (final IOException e) {
							if (packet.sentListener != null) {
								packet.sentListener.failure();
							}
							logger.error("Failed to send UDP packet", e);
						}
						Thread.sleep((1000l * packet.data.length / parent.config.maxUpstreamBytesPerSecond));
					}
				} catch (final InterruptedException e) {

				}
			}

		}
	}
}