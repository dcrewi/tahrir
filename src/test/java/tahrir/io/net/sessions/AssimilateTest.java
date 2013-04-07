package tahrir.io.net.sessions;

import java.io.File;

import org.testng.Assert;
import org.testng.annotations.Test;

import tahrir.*;
import tahrir.io.net.*;
import tahrir.tools.*;
import tahrir.tools.TrUtils.TestUtils;

public class AssimilateTest {
	@Test
	public void twoPeerTest() throws Exception {
		System.out.println("Joiner (7644) will assimilate to seed (7643)");

		final TrConfig seedConfig = new TrConfig();
		seedConfig.capabilities.allowsAssimilation = true;
		seedConfig.capabilities.allowsUnsolicitiedInbound = true;
		seedConfig.peers.runMaintainance = false;
		seedConfig.peers.runBroadcast = false;
		seedConfig.localHostName = "localhost";
		seedConfig.udp.listenPort = 7643;
		final File seedDir = TestUtils.createTempDirectory();
		final TrNode seedNode = new TrNode(seedDir, seedConfig);
		final RemoteNodeAddress seedPublicNodeId = seedNode.getRemoteNodeAddress();

		final File joinerDir = TestUtils.createTempDirectory();

		final TrConfig joinerConfig = new TrConfig();

		joinerConfig.udp.listenPort = 7644;
		joinerConfig.localHostName = "localhost";
		joinerConfig.peers.runMaintainance = true;
		joinerConfig.peers.topologyMaintenance = false;
		joinerConfig.peers.runBroadcast = false;

		final File joinerPubNodeIdsDir = new File(joinerDir, joinerConfig.publicNodeIdsDir);

		joinerPubNodeIdsDir.mkdir();

		// Ok, we should be getting this TrPeerInfo out of seedNode somehow rather
		// than needing to set its capabilities manually like this
		final TrPeerManager.TrPeerInfo seedPeerInfo = new TrPeerManager.TrPeerInfo(seedPublicNodeId);
		seedPeerInfo.capabilities = seedConfig.capabilities;

		Persistence.save(new File(joinerPubNodeIdsDir, "joiner-id"), seedPeerInfo);

		final TrNode joinerNode = new TrNode(joinerDir, joinerConfig);

		for (int x=0; x<50; x++) {
			Thread.sleep(200);
			if (joinerNode.peerManager.peers.containsKey(seedNode.getRemoteNodeAddress().physicalLocation)
					&& seedNode.peerManager.peers.containsKey(joinerNode.getRemoteNodeAddress().physicalLocation)) {
				break;
			}
		}
		// Verify that they are now connected
		Assert.assertTrue(joinerNode.peerManager.peers.containsKey(seedNode.getRemoteNodeAddress().physicalLocation), "The joiner peer manager should contain the seed peer");
		Assert.assertTrue(seedNode.peerManager.peers.containsKey(joinerNode.getRemoteNodeAddress().physicalLocation), "The seed peer manager should contain the joiner peer");
	}
}
