package tahrir;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tahrir.io.crypto.TrCrypto;
import tahrir.io.net.*;
import tahrir.io.net.microblogging.*;
import tahrir.io.net.microblogging.containers.MicroblogsForBroadcast;
import tahrir.io.net.microblogging.containers.MicroblogsForViewing;
import tahrir.io.net.sessions.AssimilateSession;
import tahrir.io.net.sessions.AssimilateSessionImpl;
import tahrir.io.net.sessions.TopologyMaintenanceSession;
import tahrir.io.net.sessions.TopologyMaintenanceSessionImpl;
import tahrir.io.net.udpV1.UdpNetworkInterface;
import tahrir.io.net.udpV1.UdpNetworkLocation;
import tahrir.tools.Persistence;
import tahrir.tools.Persistence.Modified;
import tahrir.tools.Persistence.ModifyBlock;
import tahrir.tools.Tuple2;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;



/**
 * The root class for the internal state of this node
 * 
 * @author Ian Clarke <ian.clarke@gmail.com>
 */
public class TrNode {

	Logger logger = LoggerFactory.getLogger(TrNode.class);

	public final TrConfig config;

	public File privNodeIdFile;
	public File pubNodeIdFile;
	public File publicNodeIdsDir;
	public final File rootDirectory;

	public final TrPeerManager peerManager;

	public MicrobloggingClasses mbClasses;

	public TrSessionManager sessionMgr;

	public TrNode(final File rootDirectory, final TrConfig config)
			throws SocketException {
		this.rootDirectory = rootDirectory;
		this.config = config;
		privNodeIdFile = new File(rootDirectory, config.privateNodeId);
		pubNodeIdFile = new File(rootDirectory, config.publicNodeId);
		if (!privNodeIdFile.exists()) {
			logger.info("Generating new Node ID");
			final Tuple2<PrivateNodeId, RemoteNodeAddress> kp = PrivateNodeId.generate();
			Persistence.save(privNodeIdFile, kp.a);
			Persistence.save(pubNodeIdFile, kp.b);
		}
		if (config.localHostName != null) {
			modifyPublicNodeId(new ModifyBlock<RemoteNodeAddress>() {

				public void run(final RemoteNodeAddress remoteNodeAddress, final Modified modified) {
					try {
						remoteNodeAddress.physicalLocation = new UdpNetworkLocation(InetAddress.getByName(config.localHostName),
								config.udp.listenPort);
					} catch (final UnknownHostException e) {
						logger.error("Failed to set local node address", e);
					}
				}
			});
		}
		publicNodeIdsDir = new File(rootDirectory, config.publicNodeIdsDir);
		if (!publicNodeIdsDir.exists()) {
			publicNodeIdsDir.mkdir();
		}

		logger.info("Set up UDP network interface");
		final Tuple2<RSAPublicKey, RSAPrivateKey> keyPair = Tuple2.of(getRemoteNodeAddress().publicKey,
				getPrivateNodeId().privateKey);
		final TrNetworkInterface uni = new UdpNetworkInterface(config.udp, keyPair);
		sessionMgr = new TrSessionManager(this, uni, config.capabilities.allowsUnsolicitiedInbound);

		logger.info("Set up peer manager");
		peerManager = new TrPeerManager(config.peers, this);

		registerSessions();

		if (config.peers.runBroadcast) {
			mbClasses = new MicrobloggingClasses(this);
		}
	}

	/**
	 * If you want to use a session you must register it with this method.
	 */
	private void registerSessions() {
		sessionMgr.registerSessionClass(TopologyMaintenanceSession.class, TopologyMaintenanceSessionImpl.class);
		sessionMgr.registerSessionClass(AssimilateSession.class, AssimilateSessionImpl.class);
		// don't want to be able to call broadcast methods on a seed node
		if (config.peers.runBroadcast) {
			sessionMgr.registerSessionClass(MicroblogBroadcastSession.class, MicroblogBroadcastSessionImpl.class);
		}
	}

	public ArrayList<File> getPublicNodeIdFiles() {
		return Lists.newArrayList(publicNodeIdsDir.listFiles());
	}

	public File getFileForPublicNode(final PhysicalNetworkLocation addr) {
		final int hc = Math.abs(addr.hashCode());
		return new File(publicNodeIdsDir, "pn-" + hc + ".dat");
	}

	public PrivateNodeId getPrivateNodeId() {
		return Persistence.loadReadOnly(PrivateNodeId.class, privNodeIdFile);
	}

	public void modifyPrivateNodeId(final ModifyBlock<PrivateNodeId> mb) {
		Persistence.loadAndModify(PrivateNodeId.class, privNodeIdFile, mb);
	}

	public RemoteNodeAddress getRemoteNodeAddress() {
		return Persistence.loadReadOnly(RemoteNodeAddress.class, pubNodeIdFile);
	}

	public void modifyPublicNodeId(final ModifyBlock<RemoteNodeAddress> mb) {
		Persistence.loadAndModify(RemoteNodeAddress.class, pubNodeIdFile, mb);
	}

	public static class MicrobloggingClasses {
		public final MicroblogBroadcastScheduler mbScheduler;
		public final ContactBook contactBook;
		public final ShortenedPublicKeyFinder spkFinder;
		public final IncomingMicroblogHandler incomingMbHandler;
		public final MicroblogsForBroadcast mbsForBroadcast;
		public final MicroblogsForViewing mbsForViewing;
		public final IdentityMap idMap;

		public MicrobloggingClasses(final TrNode node) {
			contactBook = new ContactBook(
					getOrCreateFile(new File(node.rootDirectory, node.config.contacts)));
			spkFinder = new ShortenedPublicKeyFinder(
					getOrCreateFile(new File(node.rootDirectory, node.config.publicKeyChars)));
			idMap = new IdentityMap(spkFinder, contactBook);
			mbsForBroadcast = new MicroblogsForBroadcast();
			mbsForViewing = new MicroblogsForViewing(contactBook);
			incomingMbHandler = new IncomingMicroblogHandler(mbsForViewing, mbsForBroadcast, contactBook, idMap);
			mbScheduler = new MicroblogBroadcastScheduler(node);
		}

		// move this somewhere else
		private File getOrCreateFile(File f) {
			if (!f.exists()) {
				try {
					f.createNewFile();
				} catch (IOException e) {
					throw new RuntimeException("Could not create file " + f);
				}
			}
			return f;
		}
	}

	public static class PrivateNodeId {
		public static Tuple2<PrivateNodeId, RemoteNodeAddress> generate() {
			final Tuple2<RSAPublicKey, RSAPrivateKey> kp = TrCrypto.createRsaKeyPair();

			final PrivateNodeId privateNodeId = new PrivateNodeId();
			privateNodeId.privateKey = kp.b;

			final RemoteNodeAddress remoteNodeAddress = new RemoteNodeAddress(null, kp.a);

			return Tuple2.of(privateNodeId, remoteNodeAddress);
		}

		public RSAPrivateKey privateKey;
	}
}
