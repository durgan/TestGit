/*
 *  Copyright 2016, 2017 DTCC, Fujitsu Australia Software Technology, IBM - All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.hyperledger.fabric.sdkintegration;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.exception.JsonQueryException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.openssl.PEMWriter;
import org.hyperledger.fabric.protos.ledger.rwset.kvrwset.KvRwset;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.Peer.PeerRole;
import org.hyperledger.fabric.sdk.TransactionRequest.Type;
import org.hyperledger.fabric.sdk.exception.*;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric.sdk.testutils.TestConfig;
import org.hyperledger.fabric_ca.sdk.EnrollmentRequest;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.HFCAInfo;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.junit.Before;
import org.junit.Test;

import javax.json.Json;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hyperledger.fabric.sdk.BlockInfo.EnvelopeType.TRANSACTION_ENVELOPE;
import static org.hyperledger.fabric.sdk.Channel.NOfEvents.createNofEvents;
import static org.hyperledger.fabric.sdk.Channel.PeerOptions.createPeerOptions;
import static org.hyperledger.fabric.sdk.Channel.TransactionOptions.createTransactionOptions;
import static org.hyperledger.fabric.sdk.testutils.TestUtils.resetConfig;
import static org.hyperledger.fabric.sdk.testutils.TestUtils.testRemovingAddingPeersOrderers;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test end to end scenario
 */
public class End2endIT {

    private static final TestConfig testConfig = TestConfig.getConfig();
    private static final String TEST_ADMIN_NAME = "admin";
    private static final String TESTUSER_1_NAME = "user1";
    private static final String TEST_FIXTURES_PATH = "src/test/fixture";
    private static final String CONFIGTXLATOR_LOCATION = testConfig.getFabricConfigTxLaterLocation();
    private static final String FOO_CHANNEL_NAME = "foo1";
    private static final String BAR_CHANNEL_NAME = "bar";

    private static final int DEPLOYWAITTIME = testConfig.getDeployWaitTime();

    private static final byte[] EXPECTED_EVENT_DATA = "!".getBytes(UTF_8);
    private static final String EXPECTED_EVENT_NAME = "event";
    private static final Map<String, String> TX_EXPECTED;


    String testName = "End2endIT";

    String CHAIN_CODE_FILEPATH = "sdkintegration/gocc/sample1";
    String CHAIN_CODE_NAME = "example_cc_go";
    String CHAIN_CODE_PATH = "github.com/example_cc";
    String CHAIN_CODE_VERSION = "1";
    String UPDTE_CHAIN_CODE_VERSION = "2";
    Type CHAIN_CODE_LANG = Type.GO_LANG;
    private static final String UPDATED_BATCH_TIMEOUT = "\"timeout\": \"6s\"";  // What we want to change it to.
    private static final String ORIGINAL_BATCH_TIMEOUT = "\"timeout\": \"2s\""; // Batch time out in configtx.yaml




    static {
        TX_EXPECTED = new HashMap<>();
        TX_EXPECTED.put("readset1", "Missing readset for channel bar block 1");
        TX_EXPECTED.put("writeset1", "Missing writeset for channel bar block 1");
    }

    private final TestConfigHelper configHelper = new TestConfigHelper();
    String testTxID = null;  // save the CC invoke TxID and use in queries
    SampleStore sampleStore = null;
    private Collection<SampleOrg> testSampleOrgs;

    public static void main(String[] args) throws IOException {
        ObjectMapper MAPPER = new ObjectMapper();

        JsonQuery q = JsonQuery.compile("{ids:[.ids|split(\",\")[]|tonumber|.+100],name}");

        JsonNode in = MAPPER.readTree("{\"ids\":\"12,15,23\",\"name\":\"jackson\",\"timestamp\":1418785331123}");
        System.out.println(in);
// {"ids": "12,15,23", "name": "jackson", "timestamp": 1418785331123}

        List<JsonNode> result = q.apply(in);
        System.out.println(result);
// [{"ids": [112, 115, 123], "name": "jackson"}]
    }

    static void out(String format, Object... args) {

        System.err.flush();
        System.out.flush();

        System.out.println(format(format, args));
        System.err.flush();
        System.out.flush();

    }
    //CHECKSTYLE.ON: Method length is 320 lines (max allowed is 150).

    static String printableString(final String string) {
        int maxLogStringLength = 64;
        if (string == null || string.length() == 0) {
            return string;
        }

        String ret = string.replaceAll("[^\\p{Print}]", "?");

        ret = ret.substring(0, Math.min(ret.length(), maxLogStringLength)) + (ret.length() > maxLogStringLength ? "..." : "");

        return ret;

    }

    @Before
    public void checkConfig() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException, MalformedURLException, org.hyperledger.fabric_ca.sdk.exception.InvalidArgumentException {
        out("\n\n\nRUNNING: %s.\n", testName);
        //   configHelper.clearConfig();
        //   assertEquals(256, Config.getConfig().getSecurityLevel());
        resetConfig();
        configHelper.customizeConfig();

        testSampleOrgs = testConfig.getIntegrationTestsSampleOrgs();
        //Set up hfca for each sample org

        for (SampleOrg sampleOrg : testSampleOrgs) {
            String caName = sampleOrg.getCAName(); //Try one of each name and no name.
            if (caName != null && !caName.isEmpty()) {
                sampleOrg.setCAClient(HFCAClient.createNewInstance(caName, sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
            } else {
                sampleOrg.setCAClient(HFCAClient.createNewInstance(sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
            }
        }
    }

    Map<String, Properties> clientTLSProperties = new HashMap<>();

    File sampleStoreFile = new File("D:\\fabric-sdk-javah\\fabric-sdk-java\\src\\test\\java\\org\\hyperledger\\fabric\\data\\HFCSampletest.properties");

    @Test
    public void setup() throws Exception {
        //Persistence is not part of SDK. Sample file store is for demonstration purposes only!
        //   MUST be replaced with more robust application implementation  (Database, LDAP)

//        if (sampleStoreFile.exists()) { //For testing start fresh
//            sampleStoreFile.delete();
//        }
        sampleStore = new SampleStore(sampleStoreFile);

        enrollUsersSetup(sampleStore); //This enrolls users with fabric ca and setups sample store to get users later.


//        runFabricTest(sampleStore); //Runs Fabric tests with constructing channels, joining peers, exercising chaincode
//        addOrgTest(sampleStore);
//        runFirstTest(sampleStore);

//        addAndUodateendor(sampleStore);

//        uodateendor(sampleStore);

        addAllProcess(sampleStore);
    }

    public void addAllProcess(SampleStore sampleStore) throws Exception{
//        创建org1channel
        HFClient client = HFClient.createNewInstance();
        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        SampleOrg sampleOrg2 = testConfig.getIntegrationTestsSampleOrg("peerOrg2");
        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());
        Channel fooChannel = constructChannel(FOO_CHANNEL_NAME, client, sampleOrg);

        //安装，实例化，set query
        runChannel(client, fooChannel, true, sampleOrg, 99);
//        动态加入org2
        addOrgProcess(client,sampleStore,fooChannel);



        //更新背书节点,更新org1合约
        updateChaincode(fooChannel,client,97,"2",sampleOrg);

        //安装org2合约,query or1旧信息，set org2新信息
        Collection<Peer> toJoinPeers = new HashSet<Peer>();

        client.setUserContext(sampleOrg2.getPeerAdmin());
        for (String peerName : sampleOrg2.getPeerNames()) {
            String peerLocation = sampleOrg2.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName);
            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            toJoinPeers.add(peer);
        }

        Collection<Peer> toQueryPeers = new HashSet<Peer>();
        client.setUserContext(sampleOrg.getPeerAdmin());
        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName);
            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            toQueryPeers.add(peer);
        }
        installChaincode(fooChannel,sampleOrg2,client, true, 99,toJoinPeers,toQueryPeers,sampleOrg);

//        org1查询org2信息

        //org2query org1旧信息

        //org2安装新合约，org1查询
    }

    private void installChaincode(Channel channel,SampleOrg sampleOrg2,HFClient client, boolean installChaincode, int delta,Collection<Peer> peers,Collection<Peer> toQueryPeers,SampleOrg sampleOrg) throws Exception {

//        Channel channel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg2);

        channel = client.getChannel(FOO_CHANNEL_NAME);

        class ChaincodeEventCapture { //A test class to capture chaincode events
            final String handle;
            final BlockEvent blockEvent;
            final ChaincodeEvent chaincodeEvent;

            ChaincodeEventCapture(String handle, BlockEvent blockEvent, ChaincodeEvent chaincodeEvent) {
                this.handle = handle;
                this.blockEvent = blockEvent;
                this.chaincodeEvent = chaincodeEvent;
            }
        }

        // The following is just a test to see if peers and orderers can be added and removed.
        // not pertinent to the code flow.
        testRemovingAddingPeersOrderers(client, channel);

        Vector<ChaincodeEventCapture> chaincodeEvents = new Vector<>(); // Test list to capture chaincode events.

        try {

            final String channelName = channel.getName();
            boolean isFooChain = FOO_CHANNEL_NAME.equals(channelName);
            out("Running channel %s", channelName);

            Collection<Orderer> orderers = channel.getOrderers();
            final ChaincodeID chaincodeID;
            Collection<ProposalResponse> responses;
            Collection<ProposalResponse> successful = new LinkedList<>();
            Collection<ProposalResponse> failed = new LinkedList<>();

            // Register a chaincode event listener that will trigger for any chaincode id and only for EXPECTED_EVENT_NAME event.

            String chaincodeEventListenerHandle = channel.registerChaincodeEventListener(Pattern.compile(".*"),
                    Pattern.compile(Pattern.quote(EXPECTED_EVENT_NAME)),
                    (handle, blockEvent, chaincodeEvent) -> {

                        chaincodeEvents.add(new ChaincodeEventCapture(handle, blockEvent, chaincodeEvent));

                        String es = blockEvent.getPeer() != null ? blockEvent.getPeer().getName() : blockEvent.getEventHub().getName();
                        out("RECEIVED Chaincode event with handle: %s, chaincode Id: %s, chaincode event name: %s, "
                                        + "transaction id: %s, event payload: \"%s\", from eventhub: %s",
                                handle, chaincodeEvent.getChaincodeId(),
                                chaincodeEvent.getEventName(),
                                chaincodeEvent.getTxId(),
                                new String(chaincodeEvent.getPayload()), es);

                    });

            //For non foo channel unregister event listener to test events are not called.
            if (!isFooChain) {
                channel.unregisterChaincodeEventListener(chaincodeEventListenerHandle);
                chaincodeEventListenerHandle = null;

            }

            ChaincodeID.Builder chaincodeIDBuilder = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                    .setVersion(CHAIN_CODE_VERSION);
            if (null != CHAIN_CODE_PATH) {
                chaincodeIDBuilder.setPath(CHAIN_CODE_PATH);

            }
            chaincodeID = chaincodeIDBuilder.build();

            if (installChaincode) {
                ////////////////////////////
                // Install Proposal Request
                //

                client.setUserContext(sampleOrg2.getPeerAdmin());

                out("Creating install proposal");

                InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
                installProposalRequest.setChaincodeID(chaincodeID);

                if (isFooChain) {
                    // on foo chain install from directory.

                    ////For GO language and serving just a single user, chaincodeSource is mostly likely the users GOPATH
                    installProposalRequest.setChaincodeSourceLocation(Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile());
                } else {
                    // On bar chain install from an input stream.

                    if (CHAIN_CODE_LANG.equals(Type.GO_LANG)) {

                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH, "src", CHAIN_CODE_PATH).toFile()),
                                Paths.get("src", CHAIN_CODE_PATH).toString()));
                    } else {
                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile()),
                                "src"));
                    }
                }

                installProposalRequest.setChaincodeVersion(CHAIN_CODE_VERSION);
                installProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);

                out("Sending install proposal");

                ////////////////////////////
                // only a client from the same org as the peer can issue an install request
                int numInstallProposal = 0;
                //    Set<String> orgs = orgPeers.keySet();
                //   for (SampleOrg org : testSampleOrgs) {

//                Collection<Peer> peers = channel.getPeers();
                numInstallProposal = numInstallProposal + peers.size();
                client.setUserContext(sampleOrg2.getPeerAdmin());
                responses = client.sendInstallProposal(installProposalRequest, peers);

                for (ProposalResponse response : responses) {
                    if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                        out("Successful install proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                        successful.add(response);
                    } else {
                        failed.add(response);
                    }
                }

                //   }
                out("Received %d install proposal responses. Successful+verified: %d . Failed: %d", numInstallProposal, successful.size(), failed.size());

                if (failed.size() > 0) {
                    ProposalResponse first = failed.iterator().next();
                    fail("Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
                }
            }

//            org2进行set操作
//            try {
//
//
//                Map<String, String> resultMap = new HashMap<>();
//
//
//                /// Send transaction proposal to all peers
//                TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
//                transactionProposalRequest.setChaincodeID(chaincodeID);
//                transactionProposalRequest.setFcn("set");
//                transactionProposalRequest.setArgs(new String[] {"e", "500", "f", "" + (200 + delta)});
//
//                Map<String, byte[]> tm2 = new HashMap<>();
//                tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
//                tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8));
//                tm2.put("result", ":)".getBytes(UTF_8));
//                transactionProposalRequest.setTransientMap(tm2);
//                Channel channeln = client.getChannel(FOO_CHANNEL_NAME);
//                Collection<ProposalResponse> transactionPropResp = channeln.sendTransactionProposal(transactionProposalRequest, channeln.getPeers());
//                for (ProposalResponse response : transactionPropResp) {
//                    if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
//                        successful.add(response);
//                    } else {
//                        failed.add(response);
//                    }
//                }
//
//                Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
//                if (proposalConsistencySets.size() != 1) {
//                    System.out.println("Expected only one set of consistent proposal responses but got " + proposalConsistencySets.size());
//                }
//
//                if (failed.size() > 0) {
//                    ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
//                    System.out.println("Not enough endorsers for inspect:" + failed.size() + " endorser error: " + firstTransactionProposalResponse.getMessage() + ". Was verified: "
//                            + firstTransactionProposalResponse.isVerified());
//                    throw new RuntimeException("Not enough endorsers for inspect:" + failed.size() + " endorser error: " + firstTransactionProposalResponse.getMessage() + ". Was verified: "
//                            + firstTransactionProposalResponse.isVerified());
//                } else {
//                    System.out.println("Successfully received transaction proposal responses.");
//                    ProposalResponse resp = transactionPropResp.iterator().next();
//                    byte[] x = resp.getChaincodeActionResponsePayload();
//                    String resultAsString = null;
//                    if (x != null) {
//                        resultAsString = new String(x, "UTF-8");
//                    }
//
//                    System.out.println("resultAsStringresultAsStringresultAsStringresultAsString："+resultAsString);
//                }
//            } catch (Exception e) {
//                throw new RuntimeException("error in invoke: " + e.getMessage(), e.getCause());
//            }






            //查询org1旧数据
            QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
            queryByChaincodeRequest.setArgs(new String[] {"b"});
            queryByChaincodeRequest.setFcn("query");
            queryByChaincodeRequest.setChaincodeID(chaincodeID);

            Map<String, byte[]> tm2 = new HashMap<>();
            tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
            tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
            queryByChaincodeRequest.setTransientMap(tm2);

//            channel = client.getChannel(FOO_CHANNEL_NAME);
            Channel channelo = client.getChannel(FOO_CHANNEL_NAME);
            Collection<ProposalResponse> queryProposals = channelo.queryByChaincode(queryByChaincodeRequest, channelo.getPeers());
            for (ProposalResponse proposalResponse : queryProposals) {
                if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                    fail("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                            ". Messages: " + proposalResponse.getMessage()
                            + ". Was verified : " + proposalResponse.isVerified());
                } else {
                    String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                    out("Query payload of b from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
                    System.out.println("org1 old vvvvvvvvvvvvvvvvvvvvv="+payload);
                }
            }


//            查询org2 set信息

            //查询org1旧数据
//            QueryByChaincodeRequest queryByChaincodeRequest2 = client.newQueryProposalRequest();
//            queryByChaincodeRequest2.setArgs(new String[] {"c"});
//            queryByChaincodeRequest2.setFcn("query");
//            queryByChaincodeRequest2.setChaincodeID(chaincodeID);
//
//            queryByChaincodeRequest.setTransientMap(tm2);
//
////            channel = client.getChannel(FOO_CHANNEL_NAME);
//            Channel channeln = client.getChannel(FOO_CHANNEL_NAME);
//            Collection<ProposalResponse> queryProposalsn = channeln.queryByChaincode(queryByChaincodeRequest2, channeln.getPeers());
//            for (ProposalResponse proposalResponse : queryProposalsn) {
//                if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
//                    fail("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
//                            ". Messages: " + proposalResponse.getMessage()
//                            + ". Was verified : " + proposalResponse.isVerified());
//                } else {
//                    String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
//                    out("Query payload of b from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
//                    System.out.println("org1 old vvvvvvvvvvvvvvvvvvvvv="+payload);
//                }
//            }

        } catch (Exception e) {
            out("Caught exception while running query");
            e.printStackTrace();
            fail("Failed during chaincode query with error : " + e.getMessage());
        }



    }

    public void  uodateendor(SampleStore sampleStore) throws Exception{
        HFClient client = HFClient.createNewInstance();
        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        //Construct and run the channels
        SampleOrg sampleOrg2 = testConfig.getIntegrationTestsSampleOrg("peerOrg2");

        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());



        Channel fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg);

        final byte[] channelConfigurationBytes = fooChannel.getChannelConfigurationBytes();

        HttpClient httpclient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(channelConfigurationBytes));

        HttpResponse response = httpclient.execute(httppost);
        int statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for decoding current channel config bytes", statuscode);
        assertEquals(200, statuscode);
        String responseAsString = EntityUtils.toString(response.getEntity());

        System.out.println("oldResponseString"+responseAsString);







//        fooChannel = client.getChannel(FOO_CHANNEL_NAME);

//        旧合约set query
//                旧合约update
//        新合约query


//        更新chaincode 及背书策略 ?
        updateChaincode(fooChannel,client,97,"2",sampleOrg2);

//        更新背书策略


//        查询

        System.out.println("end");
    }

//    加入通道，更新背书策略 （不更新合约）
    public void  addAndUodateendor(SampleStore sampleStore) throws Exception{
        HFClient client = HFClient.createNewInstance();
        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        //Construct and run the channels
        SampleOrg sampleOrg2 = testConfig.getIntegrationTestsSampleOrg("peerOrg2");

        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());



        Channel fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg);

        final byte[] channelConfigurationBytes = fooChannel.getChannelConfigurationBytes();

        HttpClient httpclient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(channelConfigurationBytes));

        HttpResponse response = httpclient.execute(httppost);
        int statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for decoding current channel config bytes", statuscode);
        assertEquals(200, statuscode);
        String responseAsString = EntityUtils.toString(response.getEntity());

        System.out.println("oldResponseString"+responseAsString);







//        加入组织
//        fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg);
        fooChannel = client.getChannel(FOO_CHANNEL_NAME);
        client.setUserContext(sampleOrg2.getPeerAdmin());
        for (String peerName : sampleOrg2.getPeerNames()) {
            String peerLocation = sampleOrg2.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName);
            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            fooChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY))); //Default is all roles.
        }

//        旧合约set query
//                旧合约update
//        新合约query

//        更新chaincode 及背书策略 ?
        updateChaincode(fooChannel,client,97,"2",sampleOrg2);

//        更新背书策略


//        查询

        System.out.println("end");
    }

    public void runFirstTest(final SampleStore sampleStore) throws Exception {

        HFClient client = HFClient.createNewInstance();
        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        //Construct and run the channels
        SampleOrg sampleOrg2 = testConfig.getIntegrationTestsSampleOrg("peerOrg2");

        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());

        Channel fooChannel = constructChannel(FOO_CHANNEL_NAME, client, sampleOrg);



//        安装合约
        runChannel(client, fooChannel, true, sampleOrg, 0);

        System.out.println("end");
    }

    public void addOrgProcess(HFClient client,final SampleStore sampleStore,Channel fooChannel) throws Exception {
        ////////////////////////////
        // Setup client
        //Create instance of client.



//        HFClient client = HFClient.createNewInstance();
//        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());


        //Construct and run the channels
        SampleOrg sampleOrg2 = testConfig.getIntegrationTestsSampleOrg("peerOrg2");

        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());


        final byte[] channelConfigurationBytes = fooChannel.getChannelConfigurationBytes();

        HttpClient httpclient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(channelConfigurationBytes));

        HttpResponse response = httpclient.execute(httppost);
        int statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for decoding current channel config bytes", statuscode);
        assertEquals(200, statuscode);
        String responseAsString = EntityUtils.toString(response.getEntity());

        System.out.println("oldResponseString"+responseAsString);



        JSONObject responseJSON = JSONObject.parseObject(responseAsString);

        File filePath = new File("/Volumes/书/tmp/org2.json");

        //读取文件
        String input = FileUtils.readFileToString(filePath, "UTF-8");
        //将读取的数据转换为JSONObject
        JSONObject newJson = JSONObject.parseObject(input);

        Map addVLUE = new HashMap(){{
            put("Org2MSP",newJson);
        }};
        replaceJsonValueWithPath(responseJSON,addVLUE,"channel_group.groups.Application.groups");
//        responseJSON.getJSONObject("channel_group").getJSONObject("groups").getJSONObject("Application").getJSONObject("groups").put("Org3MSP",newJson);
        System.out.println("modify oldResponseString"+responseJSON.toJSONString());

//        String updateString = responseAsString.replace(ORIGINAL_BATCH_TIMEOUT, UPDATED_BATCH_TIMEOUT);
        String updateString = responseJSON.toJSONString();

        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/encode/common.Config");
        httppost.setEntity(new StringEntity(updateString));

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for encoding the new desired channel config bytes", statuscode);
        assertEquals(200, statuscode);
        byte[] newConfigBytes = EntityUtils.toByteArray(response.getEntity());

        // Now send to configtxlator multipart form post with original config bytes, updated config bytes and channel name.
        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/configtxlator/compute/update-from-configs");

        HttpEntity multipartEntity = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addBinaryBody("original", channelConfigurationBytes, ContentType.APPLICATION_OCTET_STREAM, "originalFakeFilename")
                .addBinaryBody("updated", newConfigBytes, ContentType.APPLICATION_OCTET_STREAM, "updatedFakeFilename")
                .addBinaryBody("channel", fooChannel.getName().getBytes()).build();

        httppost.setEntity(multipartEntity);

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for updated config bytes needed for updateChannelConfiguration ", statuscode);
        assertEquals(200, statuscode);

        byte[] updateBytes = EntityUtils.toByteArray(response.getEntity());

        UpdateChannelConfiguration updateChannelConfiguration = new UpdateChannelConfiguration(updateBytes);


        final String sampleOrgName = sampleOrg.getName();
        final SampleUser ordererAdmin = sampleStore.getMember(sampleOrgName + "OrderAdmin", sampleOrgName);

        final SampleUser peer1Admin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName);

//        final String sampleOrg2Name = sampleOrg2.getName();
//        final SampleUser peer2Admin = sampleStore.getMember(sampleOrg2Name + "Admin", sampleOrg2Name);

        //Ok now do actual channel update.
//        fooChannel.updateChannelConfiguration(updateChannelConfiguration, client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, ordererAdmin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer2Admin));

//                fooChannel.updateChannelConfiguration(updateChannelConfiguration, client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, ordererAdmin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin));

        fooChannel.updateChannelConfiguration(updateChannelConfiguration,client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin));

        //Let's add some additional verification...

        client.setUserContext(sampleOrg.getPeerAdmin());

        final byte[] modChannelBytes = fooChannel.getChannelConfigurationBytes();

        //Now decode the new channel config bytes to json...
        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(modChannelBytes));

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        assertEquals(200, statuscode);

        responseAsString = EntityUtils.toString(response.getEntity());


        System.out.println("newResponseString"+responseAsString);


//        加入组织
//        fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg2);
        fooChannel = client.getChannel(FOO_CHANNEL_NAME);
        client.setUserContext(sampleOrg2.getPeerAdmin());
        for (String peerName : sampleOrg2.getPeerNames()) {
            String peerLocation = sampleOrg2.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName);
            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            fooChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY))); //Default is all roles.
        }

        System.out.println("end join process");
    }



    public void addOrgTest(final SampleStore sampleStore) throws Exception {
        ////////////////////////////
        // Setup client
        //Create instance of client.
        HFClient client = HFClient.createNewInstance();
        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        //Construct and run the channels
        SampleOrg sampleOrg2 = testConfig.getIntegrationTestsSampleOrg("peerOrg2");

        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());

//        Channel fooChannel = constructChannel(FOO_CHANNEL_NAME, client, sampleOrg);
//


//        安装合约
//        runChannel(client, fooChannel, true, sampleOrg, 0);
//        实例化合约

//        set

//        query


        Channel fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg);

        final byte[] channelConfigurationBytes = fooChannel.getChannelConfigurationBytes();

        HttpClient httpclient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(channelConfigurationBytes));

        HttpResponse response = httpclient.execute(httppost);
        int statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for decoding current channel config bytes", statuscode);
        assertEquals(200, statuscode);
        String responseAsString = EntityUtils.toString(response.getEntity());

        System.out.println("oldResponseString"+responseAsString);





        JSONObject responseJSON = JSONObject.parseObject(responseAsString);

        File filePath = new File("/Volumes/书/tmp/org2.json");

        //读取文件
        String input = FileUtils.readFileToString(filePath, "UTF-8");
        //将读取的数据转换为JSONObject
        JSONObject newJson = JSONObject.parseObject(input);

        Map addVLUE = new HashMap(){{
            put("Org2MSP",newJson);
        }};
        replaceJsonValueWithPath(responseJSON,addVLUE,"channel_group.groups.Application.groups");
//        responseJSON.getJSONObject("channel_group").getJSONObject("groups").getJSONObject("Application").getJSONObject("groups").put("Org3MSP",newJson);
        System.out.println("modify oldResponseString"+responseJSON.toJSONString());

//        String updateString = responseAsString.replace(ORIGINAL_BATCH_TIMEOUT, UPDATED_BATCH_TIMEOUT);
        String updateString = responseJSON.toJSONString();

        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/encode/common.Config");
        httppost.setEntity(new StringEntity(updateString));

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for encoding the new desired channel config bytes", statuscode);
        assertEquals(200, statuscode);
        byte[] newConfigBytes = EntityUtils.toByteArray(response.getEntity());

        // Now send to configtxlator multipart form post with original config bytes, updated config bytes and channel name.
        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/configtxlator/compute/update-from-configs");

        HttpEntity multipartEntity = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addBinaryBody("original", channelConfigurationBytes, ContentType.APPLICATION_OCTET_STREAM, "originalFakeFilename")
                .addBinaryBody("updated", newConfigBytes, ContentType.APPLICATION_OCTET_STREAM, "updatedFakeFilename")
                .addBinaryBody("channel", fooChannel.getName().getBytes()).build();

        httppost.setEntity(multipartEntity);

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for updated config bytes needed for updateChannelConfiguration ", statuscode);
        assertEquals(200, statuscode);

        byte[] updateBytes = EntityUtils.toByteArray(response.getEntity());

        UpdateChannelConfiguration updateChannelConfiguration = new UpdateChannelConfiguration(updateBytes);


        final String sampleOrgName = sampleOrg.getName();
        final SampleUser ordererAdmin = sampleStore.getMember(sampleOrgName + "OrderAdmin", sampleOrgName);

        final SampleUser peer1Admin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName);

//        final String sampleOrg2Name = sampleOrg2.getName();
//        final SampleUser peer2Admin = sampleStore.getMember(sampleOrg2Name + "Admin", sampleOrg2Name);

        //Ok now do actual channel update.
//        fooChannel.updateChannelConfiguration(updateChannelConfiguration, client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, ordererAdmin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer2Admin));

//                fooChannel.updateChannelConfiguration(updateChannelConfiguration, client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, ordererAdmin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin));

        fooChannel.updateChannelConfiguration(updateChannelConfiguration,client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin));

        //Let's add some additional verification...

        client.setUserContext(sampleOrg.getPeerAdmin());

        final byte[] modChannelBytes = fooChannel.getChannelConfigurationBytes();

        //Now decode the new channel config bytes to json...
        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(modChannelBytes));

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        assertEquals(200, statuscode);

        responseAsString = EntityUtils.toString(response.getEntity());


        System.out.println("newResponseString"+responseAsString);


//        加入组织
//        fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg);
        fooChannel = client.getChannel(FOO_CHANNEL_NAME);
        client.setUserContext(sampleOrg2.getPeerAdmin());
        for (String peerName : sampleOrg2.getPeerNames()) {
            String peerLocation = sampleOrg2.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName);
            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            fooChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY))); //Default is all roles.
        }

//        旧合约set query
//                旧合约update
//        新合约query


//        更新chaincode 及背书策略
        updateChaincode(fooChannel,client,97,"2",sampleOrg);

//        更新背书策略


//        查询

        System.out.println("end");

//        for (String peerName : sampleOrg2.getPeerNames()) {
//            String peerLocation = sampleOrg2.getPeerLocation(peerName);
//
//            Properties peerProperties = testConfig.getPeerProperties(peerName);
//            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
//            fooChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY, PeerRole.EVENT_SOURCE))); //Default is all roles.
//
//        }
//
//        System.out.println("finish");

    }
    public void runFabricTest(final SampleStore sampleStore) throws Exception {

        ////////////////////////////
        // Setup client

        //Create instance of client.
        HFClient client = HFClient.createNewInstance();

        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());


        ////////////////////////////
        //Construct and run the channels
        SampleOrg sampleOrg2 = testConfig.getIntegrationTestsSampleOrg("peerOrg2");

        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        client.setUserContext(sampleOrg.getPeerAdmin());



//        Channel fooChannel = constructChannel(FOO_CHANNEL_NAME, client, sampleOrg2);






        Channel fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg);






//        sampleStore.saveChannel(fooChannel);
//        runChannel(client, fooChannel, true, sampleOrg, 0);
//         Channel  fooChannel=Channel.createNewInstance("foo",client);

        // Getting foo channels current configuration bytes.
        final byte[] channelConfigurationBytes = fooChannel.getChannelConfigurationBytes();

        HttpClient httpclient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(channelConfigurationBytes));

        HttpResponse response = httpclient.execute(httppost);
        int statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for decoding current channel config bytes", statuscode);
        assertEquals(200, statuscode);
        String responseAsString = EntityUtils.toString(response.getEntity());

        System.out.println("oldResponseString"+responseAsString);

        client.setUserContext(sampleOrg2.getPeerAdmin());



        for (String peerName : sampleOrg2.getPeerNames()) {
            String peerLocation = sampleOrg2.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName);
            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            fooChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY))); //Default is all roles.

        }

        System.out.println("OK扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩扩");






        JSONObject responseJSON = JSONObject.parseObject(responseAsString);

//        //抽取org1作为模板

//        JSONObject org1Json = responseJSON.getJSONObject("channel_group").getJSONObject("groups").getJSONObject("Application").getJSONObject("groups").getJSONObject("Org1MSP");
//        String org1str = org1Json.toJSONString();
//        JSONObject newJson = JSONObject.parseObject(org1str);
//
//
//        //修改模板中的数据
//        //替换values.config.name值
//        Map modifyVLUE = new HashMap(){{
//            put("name","Org2MSP");
//        }};
//        replaceJsonValueWithPath(newJson,modifyVLUE,"values.MSP.value.config");


//        ObjectMapper MAPPER = new ObjectMapper();
//        JsonQuery q = JsonQuery.compile("capture(\"(?<a>[a-z]+)-(?<n>[0-9]+)\")");
//        JsonNode in = MAPPER.readTree(newJson.toJSONString());
//        System.out.println(in);
//        List<JsonNode> result = q.apply(in);
//        System.out.println(result);

        //加入到原先的对象中

        File filePath = new File("D:\\org2.json");

        //读取文件
        String input = FileUtils.readFileToString(filePath, "UTF-8");
        //将读取的数据转换为JSONObject
        JSONObject newJson = JSONObject.parseObject(input);

        Map addVLUE = new HashMap(){{
            put("Org2MSP",newJson);
        }};
        replaceJsonValueWithPath(responseJSON,addVLUE,"channel_group.groups.Application.groups");
//        responseJSON.getJSONObject("channel_group").getJSONObject("groups").getJSONObject("Application").getJSONObject("groups").put("Org3MSP",newJson);
        System.out.println("modify oldResponseString"+responseJSON.toJSONString());

//        String updateString = responseAsString.replace(ORIGINAL_BATCH_TIMEOUT, UPDATED_BATCH_TIMEOUT);
        String updateString = responseJSON.toJSONString();

        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/encode/common.Config");
        httppost.setEntity(new StringEntity(updateString));

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for encoding the new desired channel config bytes", statuscode);
        assertEquals(200, statuscode);
        byte[] newConfigBytes = EntityUtils.toByteArray(response.getEntity());

        // Now send to configtxlator multipart form post with original config bytes, updated config bytes and channel name.
        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/configtxlator/compute/update-from-configs");

        HttpEntity multipartEntity = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addBinaryBody("original", channelConfigurationBytes, ContentType.APPLICATION_OCTET_STREAM, "originalFakeFilename")
                .addBinaryBody("updated", newConfigBytes, ContentType.APPLICATION_OCTET_STREAM, "updatedFakeFilename")
                .addBinaryBody("channel", fooChannel.getName().getBytes()).build();

        httppost.setEntity(multipartEntity);

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        out("Got %s status for updated config bytes needed for updateChannelConfiguration ", statuscode);
        assertEquals(200, statuscode);

        byte[] updateBytes = EntityUtils.toByteArray(response.getEntity());

        UpdateChannelConfiguration updateChannelConfiguration = new UpdateChannelConfiguration(updateBytes);

        //To change the channel we need to sign with orderer admin certs which crypto gen stores:

        // private key: src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/ordererOrganizations/example.com/users/Admin@example.com/msp/keystore/f1a9a940f57419a18a83a852884790d59b378281347dd3d4a88c2b820a0f70c9_sk
        //certificate:  src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/ordererOrganizations/example.com/users/Admin@example.com/msp/signcerts/Admin@example.com-cert.pem

        final String sampleOrgName = sampleOrg.getName();
        final SampleUser ordererAdmin = sampleStore.getMember(sampleOrgName + "OrderAdmin", sampleOrgName);

        final SampleUser peer1Admin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName);

//        final String sampleOrg2Name = sampleOrg2.getName();
//        final SampleUser peer2Admin = sampleStore.getMember(sampleOrg2Name + "Admin", sampleOrg2Name);

        //Ok now do actual channel update.
//        fooChannel.updateChannelConfiguration(updateChannelConfiguration, client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, ordererAdmin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer2Admin));

//                fooChannel.updateChannelConfiguration(updateChannelConfiguration, client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, ordererAdmin),client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin));

        fooChannel.updateChannelConfiguration(updateChannelConfiguration,client.getUpdateChannelConfigurationSignature(updateChannelConfiguration, peer1Admin));

        //Let's add some additional verification...

        client.setUserContext(sampleOrg.getPeerAdmin());

        final byte[] modChannelBytes = fooChannel.getChannelConfigurationBytes();

        //Now decode the new channel config bytes to json...
        httppost = new HttpPost(CONFIGTXLATOR_LOCATION + "/protolator/decode/common.Config");
        httppost.setEntity(new ByteArrayEntity(modChannelBytes));

        response = httpclient.execute(httppost);
        statuscode = response.getStatusLine().getStatusCode();
        assertEquals(200, statuscode);

        responseAsString = EntityUtils.toString(response.getEntity());

//        if (!responseAsString.contains(UPDATED_BATCH_TIMEOUT)) {
//            //If it doesn't have the updated time out it failed.
//            fail(format("Did not find updated expected batch timeout '%s', in:%s", UPDATED_BATCH_TIMEOUT, responseAsString));
//        }
//
//        if (responseAsString.contains(ORIGINAL_BATCH_TIMEOUT)) { //Should not have been there anymore!
//
//            fail(format("Found original batch timeout '%s', when it was not expected in:%s", ORIGINAL_BATCH_TIMEOUT, responseAsString));
//        }

        System.out.println("newResponseString"+responseAsString);



//        fooChannel = reConstructChannel(FOO_CHANNEL_NAME, client, sampleOrg);

        System.out.println("start join");



//        for (String peerName : sampleOrg2.getPeerNames()) {
//            String peerLocation = sampleOrg2.getPeerLocation(peerName);
//
//            Properties peerProperties = testConfig.getPeerProperties(peerName);
//            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
//            fooChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY, PeerRole.EVENT_SOURCE))); //Default is all roles.
//
//        }
//
//        System.out.println("finish");




    }

    /**
     * 根据路径替换对应的值
     * @param newJson
     * @param modifyVLUE
     * @param path
     */
    private void replaceJsonValueWithPath(JSONObject newJson, Map modifyVLUE, String path) {
        String[] pathArr = path.split("\\.");
        JSONObject resultJson = newJson;
        for(String p:pathArr){
            resultJson = resultJson.getJSONObject(p);
        }
        resultJson.putAll(modifyVLUE);
    }

    /**
     * Will register and enroll users persisting them to samplestore.
     *
     * @param sampleStore
     * @throws Exception
     */
    public void enrollUsersSetup(SampleStore sampleStore) throws Exception {
        ////////////////////////////
        //Set up USERS

        //SampleUser can be any implementation that implements org.hyperledger.fabric.sdk.User Interface

        ////////////////////////////
        // get users for all orgs

        for (SampleOrg sampleOrg : testSampleOrgs) {

            HFCAClient ca = sampleOrg.getCAClient();

            final String orgName = sampleOrg.getName();
            final String mspid = sampleOrg.getMSPID();
            ca.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

            if (testConfig.isRunningFabricTLS()) {
                //This shows how to get a client TLS certificate from Fabric CA
                // we will use one client TLS certificate for orderer peers etc.
                final EnrollmentRequest enrollmentRequestTLS = new EnrollmentRequest();
                enrollmentRequestTLS.addHost("localhost");
                enrollmentRequestTLS.setProfile("tls");
                final Enrollment enroll = ca.enroll("admin", "adminpw", enrollmentRequestTLS);
                final String tlsCertPEM = enroll.getCert();
                final String tlsKeyPEM = getPEMStringFromPrivateKey(enroll.getKey());

                final Properties tlsProperties = new Properties();

                System.out.println("tlsKeyPEM"+tlsKeyPEM);
                System.out.println("clientCertBytes"+tlsCertPEM);

                tlsProperties.put("clientKeyBytes", tlsKeyPEM.getBytes(UTF_8));
                tlsProperties.put("clientCertBytes", tlsCertPEM.getBytes(UTF_8));
                clientTLSProperties.put(sampleOrg.getName(), tlsProperties);
                //Save in samplestore for follow on tests.
                sampleStore.storeClientPEMTLCertificate(sampleOrg, tlsCertPEM);
                sampleStore.storeClientPEMTLSKey(sampleOrg, tlsKeyPEM);
            }

            HFCAInfo info = ca.info(); //just check if we connect at all.
            assertNotNull(info);
            String infoName = info.getCAName();
            if (infoName != null && !infoName.isEmpty()) {
                assertEquals(ca.getCAName(), infoName);
            }

            SampleUser admin = sampleStore.getMember(TEST_ADMIN_NAME, orgName);
            if (!admin.isEnrolled()) {  //Preregistered admin only needs to be enrolled with Fabric caClient.
                admin.setEnrollment(ca.enroll(admin.getName(), "adminpw"));
                admin.setMspId(mspid);
            }

            sampleOrg.setAdmin(admin); // The admin of this org --

            SampleUser user = sampleStore.getMember(TESTUSER_1_NAME, sampleOrg.getName());
            if (!user.isRegistered()) {  // users need to be registered AND enrolled
                RegistrationRequest rr = new RegistrationRequest(user.getName(), "org1.department1");
                user.setEnrollmentSecret(ca.register(rr, admin));
            }
            if (!user.isEnrolled()) {
                user.setEnrollment(ca.enroll(user.getName(), user.getEnrollmentSecret()));
                user.setMspId(mspid);
            }
            sampleOrg.addUser(user); //Remember user belongs to this Org

            final String sampleOrgName = sampleOrg.getName();
            final String sampleOrgDomainName = sampleOrg.getDomainName();

            // src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/

            SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                    Util.findFileSk(Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/",
                            sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
                    Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/", sampleOrgDomainName,
                            format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName)).toFile());

            sampleOrg.setPeerAdmin(peerOrgAdmin); //A special user that can create channels, join peers and install chaincode

        }


    }

    static String getPEMStringFromPrivateKey(PrivateKey privateKey) throws IOException {
        StringWriter pemStrWriter = new StringWriter();
        PEMWriter pemWriter = new PEMWriter(pemStrWriter);

        pemWriter.writeObject(privateKey);

        pemWriter.close();

        return pemStrWriter.toString();
    }

    //CHECKSTYLE.OFF: Method length is 320 lines (max allowed is 150).
    void runChannel(HFClient client, Channel channel, boolean installChaincode, SampleOrg sampleOrg, int delta) {

        class ChaincodeEventCapture { //A test class to capture chaincode events
            final String handle;
            final BlockEvent blockEvent;
            final ChaincodeEvent chaincodeEvent;

            ChaincodeEventCapture(String handle, BlockEvent blockEvent, ChaincodeEvent chaincodeEvent) {
                this.handle = handle;
                this.blockEvent = blockEvent;
                this.chaincodeEvent = chaincodeEvent;
            }
        }

        // The following is just a test to see if peers and orderers can be added and removed.
        // not pertinent to the code flow.
        testRemovingAddingPeersOrderers(client, channel);

        Vector<ChaincodeEventCapture> chaincodeEvents = new Vector<>(); // Test list to capture chaincode events.

        try {

            final String channelName = channel.getName();
            boolean isFooChain = FOO_CHANNEL_NAME.equals(channelName);
            out("Running channel %s", channelName);

            Collection<Orderer> orderers = channel.getOrderers();
            final ChaincodeID chaincodeID;
            Collection<ProposalResponse> responses;
            Collection<ProposalResponse> successful = new LinkedList<>();
            Collection<ProposalResponse> failed = new LinkedList<>();

            // Register a chaincode event listener that will trigger for any chaincode id and only for EXPECTED_EVENT_NAME event.

            String chaincodeEventListenerHandle = channel.registerChaincodeEventListener(Pattern.compile(".*"),
                    Pattern.compile(Pattern.quote(EXPECTED_EVENT_NAME)),
                    (handle, blockEvent, chaincodeEvent) -> {

                        chaincodeEvents.add(new ChaincodeEventCapture(handle, blockEvent, chaincodeEvent));

                        String es = blockEvent.getPeer() != null ? blockEvent.getPeer().getName() : blockEvent.getEventHub().getName();
                        out("RECEIVED Chaincode event with handle: %s, chaincode Id: %s, chaincode event name: %s, "
                                        + "transaction id: %s, event payload: \"%s\", from eventhub: %s",
                                handle, chaincodeEvent.getChaincodeId(),
                                chaincodeEvent.getEventName(),
                                chaincodeEvent.getTxId(),
                                new String(chaincodeEvent.getPayload()), es);

                    });

            //For non foo channel unregister event listener to test events are not called.
            if (!isFooChain) {
                channel.unregisterChaincodeEventListener(chaincodeEventListenerHandle);
                chaincodeEventListenerHandle = null;

            }

            ChaincodeID.Builder chaincodeIDBuilder = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                    .setVersion(CHAIN_CODE_VERSION);
            if (null != CHAIN_CODE_PATH) {
                chaincodeIDBuilder.setPath(CHAIN_CODE_PATH);

            }
            chaincodeID = chaincodeIDBuilder.build();

            if (installChaincode) {
                ////////////////////////////
                // Install Proposal Request
                //

                client.setUserContext(sampleOrg.getPeerAdmin());

                out("Creating install proposal");

                InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
                installProposalRequest.setChaincodeID(chaincodeID);

                if (isFooChain) {
                    // on foo chain install from directory.

                    ////For GO language and serving just a single user, chaincodeSource is mostly likely the users GOPATH
                    installProposalRequest.setChaincodeSourceLocation(Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile());
                } else {
                    // On bar chain install from an input stream.

                    if (CHAIN_CODE_LANG.equals(Type.GO_LANG)) {

                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH, "src", CHAIN_CODE_PATH).toFile()),
                                Paths.get("src", CHAIN_CODE_PATH).toString()));
                    } else {
                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile()),
                                "src"));
                    }
                }

                installProposalRequest.setChaincodeVersion(CHAIN_CODE_VERSION);
                installProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);

                out("Sending install proposal");

                ////////////////////////////
                // only a client from the same org as the peer can issue an install request
                int numInstallProposal = 0;
                //    Set<String> orgs = orgPeers.keySet();
                //   for (SampleOrg org : testSampleOrgs) {

                Collection<Peer> peers = channel.getPeers();
                numInstallProposal = numInstallProposal + peers.size();
                responses = client.sendInstallProposal(installProposalRequest, peers);

                for (ProposalResponse response : responses) {
                    if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                        out("Successful install proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                        successful.add(response);
                    } else {
                        failed.add(response);
                    }
                }

                //   }
                out("Received %d install proposal responses. Successful+verified: %d . Failed: %d", numInstallProposal, successful.size(), failed.size());

                if (failed.size() > 0) {
                    ProposalResponse first = failed.iterator().next();
                    fail("Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
                }
            }







            //   client.setUserContext(sampleOrg.getUser(TEST_ADMIN_NAME));
            //  final ChaincodeID chaincodeID = firstInstallProposalResponse.getChaincodeID();
            // Note installing chaincode does not require transaction no need to
            // send to Orderers

            ///////////////
            //// Instantiate chaincode.
            InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
            instantiateProposalRequest.setProposalWaitTime(DEPLOYWAITTIME);
            instantiateProposalRequest.setChaincodeID(chaincodeID);
            instantiateProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);
            instantiateProposalRequest.setFcn("init");
            instantiateProposalRequest.setArgs(new String[] {"a", "500", "b", "" + (200 + delta)});
            Map<String, byte[]> tm = new HashMap<>();
            tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
            tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
            instantiateProposalRequest.setTransientMap(tm);

            /*
              policy OR(Org1MSP.member, Org2MSP.member) meaning 1 signature from someone in either Org1 or Org2
              See README.md Chaincode endorsement policies section for more details.
            */
            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
            chaincodeEndorsementPolicy.fromYamlFile(new File(TEST_FIXTURES_PATH + "/sdkintegration/chaincodeendorsementpolicy.yaml"));
            instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

            out("Sending instantiateProposalRequest to all peers with arguments: a and b set to 100 and %s respectively", "" + (200 + delta));
            successful.clear();
            failed.clear();

            if (isFooChain) {  //Send responses both ways with specifying peers and by using those on the channel.
                responses = channel.sendInstantiationProposal(instantiateProposalRequest, channel.getPeers());
            } else {
                responses = channel.sendInstantiationProposal(instantiateProposalRequest);
            }
            for (ProposalResponse response : responses) {
                if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);
                    out("Succesful instantiate proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                } else {
                    failed.add(response);
                }
            }
            out("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());
            if (failed.size() > 0) {
                for (ProposalResponse fail : failed) {

                    out("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + fail.getMessage() + ", on peer" + fail.getPeer());

                }
                ProposalResponse first = failed.iterator().next();
                fail("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
            }

            ///////////////
            /// Send instantiate transaction to orderer
            out("Sending instantiateTransaction to orderer with a and b set to 100 and %s respectively", "" + (200 + delta));

            //Specify what events should complete the interest in this transaction. This is the default
            // for all to complete. It's possible to specify many different combinations like
            //any from a group, all from one group and just one from another or even None(NOfEvents.createNoEvents).
            // See. Channel.NOfEvents
            Channel.NOfEvents nOfEvents = createNofEvents();
            if (!channel.getPeers(EnumSet.of(PeerRole.EVENT_SOURCE)).isEmpty()) {
                nOfEvents.addPeers(channel.getPeers(EnumSet.of(PeerRole.EVENT_SOURCE)));
            }
            if (!channel.getEventHubs().isEmpty()) {
                nOfEvents.addEventHubs(channel.getEventHubs());
            }

            channel.sendTransaction(successful, createTransactionOptions() //Basically the default options but shows it's usage.
                    .userContext(client.getUserContext()) //could be a different user context. this is the default.
                    .shuffleOrders(false) // don't shuffle any orderers the default is true.
                    .orderers(channel.getOrderers()) // specify the orderers we want to try this transaction. Fails once all Orderers are tried.
                    .nOfEvents(nOfEvents) // The events to signal the completion of the interest in the transaction
            ).thenApply(transactionEvent -> {

                waitOnFabric(0);

                assertTrue(transactionEvent.isValid()); // must be valid to be here.

                assertNotNull(transactionEvent.getSignature()); //musth have a signature.
                BlockEvent blockEvent = transactionEvent.getBlockEvent(); // This is the blockevent that has this transaction.
                assertNotNull(blockEvent.getBlock()); // Make sure the RAW Fabric block is returned.

                out("Finished instantiate transaction with transaction id %s", transactionEvent.getTransactionID());

                try {
                    assertEquals(blockEvent.getChannelId(), channel.getName());
                    successful.clear();
                    failed.clear();

                    client.setUserContext(sampleOrg.getUser(TESTUSER_1_NAME));

                    ///////////////
                    /// Send transaction proposal to all peers
                    TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
                    transactionProposalRequest.setChaincodeID(chaincodeID);
                    transactionProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);
                    //transactionProposalRequest.setFcn("invoke");
                    transactionProposalRequest.setFcn("move");
                    transactionProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
                    transactionProposalRequest.setArgs("a", "b", "100");

                    Map<String, byte[]> tm2 = new HashMap<>();
                    tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8)); //Just some extra junk in transient map
                    tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8)); // ditto
                    tm2.put("result", ":)".getBytes(UTF_8));  // This should be returned see chaincode why.
                    tm2.put(EXPECTED_EVENT_NAME, EXPECTED_EVENT_DATA);  //This should trigger an event see chaincode why.

                    transactionProposalRequest.setTransientMap(tm2);

                    out("sending transactionProposal to all peers with arguments: move(a,b,100)");

                    //  Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposalToEndorsers(transactionProposalRequest);
                    Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposal(transactionProposalRequest, channel.getPeers());
                    for (ProposalResponse response : transactionPropResp) {
                        if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                            out("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                            successful.add(response);
                        } else {
                            failed.add(response);
                        }
                    }

                    // Check that all the proposals are consistent with each other. We should have only one set
                    // where all the proposals above are consistent. Note the when sending to Orderer this is done automatically.
                    //  Shown here as an example that applications can invoke and select.
                    // See org.hyperledger.fabric.sdk.proposal.consistency_validation config property.
                    Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
                    if (proposalConsistencySets.size() != 1) {
                        fail(format("Expected only one set of consistent proposal responses but got %d", proposalConsistencySets.size()));
                    }

                    out("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
                            transactionPropResp.size(), successful.size(), failed.size());
                    if (failed.size() > 0) {
                        ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
                        fail("Not enough endorsers for invoke(move a,b,100):" + failed.size() + " endorser error: " +
                                firstTransactionProposalResponse.getMessage() +
                                ". Was verified: " + firstTransactionProposalResponse.isVerified());
                    }
                    out("Successfully received transaction proposal responses.");

                    ProposalResponse resp = successful.iterator().next();
                    byte[] x = resp.getChaincodeActionResponsePayload(); // This is the data returned by the chaincode.
                    String resultAsString = null;
                    if (x != null) {
                        resultAsString = new String(x, "UTF-8");
                    }
                    assertEquals(":)", resultAsString);

                    assertEquals(200, resp.getChaincodeActionResponseStatus()); //Chaincode's status.

                    TxReadWriteSetInfo readWriteSetInfo = resp.getChaincodeActionResponseReadWriteSetInfo();
                    //See blockwalker below how to transverse this
                    assertNotNull(readWriteSetInfo);
                    assertTrue(readWriteSetInfo.getNsRwsetCount() > 0);

                    ChaincodeID cid = resp.getChaincodeID();
                    assertNotNull(cid);
                    final String path = cid.getPath();
                    if (null == CHAIN_CODE_PATH) {
                        assertTrue(path == null || "".equals(path));

                    } else {

                        assertEquals(CHAIN_CODE_PATH, path);

                    }

                    assertEquals(CHAIN_CODE_NAME, cid.getName());
                    assertEquals(CHAIN_CODE_VERSION, cid.getVersion());

                    ////////////////////////////
                    // Send Transaction Transaction to orderer
                    out("Sending chaincode transaction(move a,b,100) to orderer.");
                    return channel.sendTransaction(successful).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);

                } catch (Exception e) {
                    out("Caught an exception while invoking chaincode");
                    e.printStackTrace();
                    fail("Failed invoking chaincode with error : " + e.getMessage());
                }

                return null;

            }).thenApply(transactionEvent -> {
                try {

                    waitOnFabric(0);

                    assertTrue(transactionEvent.isValid()); // must be valid to be here.
                    out("Finished transaction with transaction id %s", transactionEvent.getTransactionID());
                    testTxID = transactionEvent.getTransactionID(); // used in the channel queries later

                    ////////////////////////////
                    // Send Query Proposal to all peers
                    //
                    String expect = "" + (300 + delta);
                    out("Now query chaincode for the value of b.");
                    QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
                    queryByChaincodeRequest.setArgs(new String[] {"b"});
                    queryByChaincodeRequest.setFcn("query");
                    queryByChaincodeRequest.setChaincodeID(chaincodeID);

                    Map<String, byte[]> tm2 = new HashMap<>();
                    tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
                    tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
                    queryByChaincodeRequest.setTransientMap(tm2);

                    Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest, channel.getPeers());
                    for (ProposalResponse proposalResponse : queryProposals) {
                        if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                            fail("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                                    ". Messages: " + proposalResponse.getMessage()
                                    + ". Was verified : " + proposalResponse.isVerified());
                        } else {
                            String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                            out("Query payload of b from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
                            assertEquals(payload, expect);
                        }
                    }

                    return null;
                } catch (Exception e) {
                    out("Caught exception while running query");
                    e.printStackTrace();
                    fail("Failed during chaincode query with error : " + e.getMessage());
                }

                return null;
            }).exceptionally(e -> {
                if (e instanceof TransactionEventException) {
                    BlockEvent.TransactionEvent te = ((TransactionEventException) e).getTransactionEvent();
                    if (te != null) {
                        throw new AssertionError(format("Transaction with txid %s failed. %s", te.getTransactionID(), e.getMessage()), e);
                    }
                }

                throw new AssertionError(format("Test failed with %s exception %s", e.getClass().getName(), e.getMessage()), e);

            }).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);

            // Channel queries

            // We can only send channel queries to peers that are in the same org as the SDK user context
            // Get the peers from the current org being used and pick one randomly to send the queries to.
            //  Set<Peer> peerSet = sampleOrg.getPeers();
            //  Peer queryPeer = peerSet.iterator().next();
            //   out("Using peer %s for channel queries", queryPeer.getName());

            BlockchainInfo channelInfo = channel.queryBlockchainInfo();
            out("Channel info for : " + channelName);
            out("Channel height: " + channelInfo.getHeight());
            String chainCurrentHash = Hex.encodeHexString(channelInfo.getCurrentBlockHash());
            String chainPreviousHash = Hex.encodeHexString(channelInfo.getPreviousBlockHash());
            out("Chain current block hash: " + chainCurrentHash);
            out("Chainl previous block hash: " + chainPreviousHash);

            // Query by block number. Should return latest block, i.e. block number 2
            BlockInfo returnedBlock = channel.queryBlockByNumber(channelInfo.getHeight() - 1);
            String previousHash = Hex.encodeHexString(returnedBlock.getPreviousHash());
            out("queryBlockByNumber returned correct block with blockNumber " + returnedBlock.getBlockNumber()
                    + " \n previous_hash " + previousHash);
            assertEquals(channelInfo.getHeight() - 1, returnedBlock.getBlockNumber());
            assertEquals(chainPreviousHash, previousHash);

            // Query by block hash. Using latest block's previous hash so should return block number 1
            byte[] hashQuery = returnedBlock.getPreviousHash();
            returnedBlock = channel.queryBlockByHash(hashQuery);
            out("queryBlockByHash returned block with blockNumber " + returnedBlock.getBlockNumber());
            assertEquals(channelInfo.getHeight() - 2, returnedBlock.getBlockNumber());

            // Query block by TxID. Since it's the last TxID, should be block 2
            returnedBlock = channel.queryBlockByTransactionID(testTxID);
            out("queryBlockByTxID returned block with blockNumber " + returnedBlock.getBlockNumber());
            assertEquals(channelInfo.getHeight() - 1, returnedBlock.getBlockNumber());

            // query transaction by ID
            TransactionInfo txInfo = channel.queryTransactionByID(testTxID);
            out("QueryTransactionByID returned TransactionInfo: txID " + txInfo.getTransactionID()
                    + "\n     validation code " + txInfo.getValidationCode().getNumber());

            if (chaincodeEventListenerHandle != null) {

                channel.unregisterChaincodeEventListener(chaincodeEventListenerHandle);
                //Should be two. One event in chaincode and two notification for each of the two event hubs

                final int numberEventsExpected = channel.getEventHubs().size() +
                        channel.getPeers(EnumSet.of(PeerRole.EVENT_SOURCE)).size();
                //just make sure we get the notifications.
                for (int i = 15; i > 0; --i) {
                    if (chaincodeEvents.size() == numberEventsExpected) {
                        break;
                    } else {
                        Thread.sleep(90); // wait for the events.
                    }

                }
                assertEquals(numberEventsExpected, chaincodeEvents.size());

                for (ChaincodeEventCapture chaincodeEventCapture : chaincodeEvents) {
                    assertEquals(chaincodeEventListenerHandle, chaincodeEventCapture.handle);
                    assertEquals(testTxID, chaincodeEventCapture.chaincodeEvent.getTxId());
                    assertEquals(EXPECTED_EVENT_NAME, chaincodeEventCapture.chaincodeEvent.getEventName());
                    assertTrue(Arrays.equals(EXPECTED_EVENT_DATA, chaincodeEventCapture.chaincodeEvent.getPayload()));
                    assertEquals(CHAIN_CODE_NAME, chaincodeEventCapture.chaincodeEvent.getChaincodeId());

                    BlockEvent blockEvent = chaincodeEventCapture.blockEvent;
                    assertEquals(channelName, blockEvent.getChannelId());
                    //   assertTrue(channel.getEventHubs().contains(blockEvent.getEventHub()));

                }

            } else {
                assertTrue(chaincodeEvents.isEmpty());
            }

            out("Running for Channel %s done", channelName);

        } catch (Exception e) {
            out("Caught an exception running channel %s", channel.getName());
            e.printStackTrace();
            fail("Test failed with error : " + e.getMessage());
        }
    }


    boolean updateChaincode(Channel channel,HFClient client,int delta,String newVersion,SampleOrg sampleOrg) throws InvalidArgumentException {

        ChaincodeID.Builder chaincodeIDBuilder = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                .setVersion(newVersion);
        if (null != CHAIN_CODE_PATH) {
            chaincodeIDBuilder.setPath(CHAIN_CODE_PATH);

        }
        ChaincodeID chaincodeID = chaincodeIDBuilder.build();

        //更新
        Collection<ProposalResponse> responses;
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();

        client.setUserContext(sampleOrg.getPeerAdmin());

        UpgradeProposalRequest upgradeProposalRequest = client.newUpgradeProposalRequest();
        upgradeProposalRequest.setProposalWaitTime(120000L);
        upgradeProposalRequest.setChaincodeID(chaincodeID);
        upgradeProposalRequest.setFcn("init");
        upgradeProposalRequest.setArgs(new String[] {"c", "500", "d", "" + (200 + delta)});

        Map<String, byte[]> tm = new HashMap<>();
        tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
        tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
        try {
            upgradeProposalRequest.setTransientMap(tm);

            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
            chaincodeEndorsementPolicy.fromYamlFile(new File(TEST_FIXTURES_PATH + "/sdkintegration/jzwchaincodeendorsementpolicy.yaml"));
            upgradeProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);
            responses = channel.sendUpgradeProposal(upgradeProposalRequest, channel.getPeers());
            for (ProposalResponse response : responses) {
                if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);
                    //logger.debug("Succesful instantiate proposal response Txid: " + response.getTransactionID() + " from peer " + response.getPeer().getName());
                } else {
                    failed.add(response);
                }
            }
            if (failed.size() > 0) {
                ProposalResponse first = failed.iterator().next();
                System.out.println("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
            }
            Collection<Orderer> orderers = channel.getOrderers();
            channel.sendTransaction(successful, orderers);
//            this.chaincodeID = chaincodeID;
            if (failed == null || (failed != null && failed.size() == 0)) {
                return true;
            } else {
                return false;
            }
        } catch (ChaincodeEndorsementPolicyParseException e) {
            throw new RuntimeException(e);
        } catch (ProposalException e) {
            throw new RuntimeException(e);
        } catch (InvalidArgumentException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    Channel constructChannel(String name, HFClient client, SampleOrg sampleOrg) throws Exception {
        ////////////////////////////
        //Construct the channel
        //

        out("Constructing channel %s", name);

        //boolean doPeerEventing = false;
        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && BAR_CHANNEL_NAME.equals(name);
//        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && FOO_CHANNEL_NAME.equals(name);
        //Only peer Admin org
        client.setUserContext(sampleOrg.getPeerAdmin());

        Collection<Orderer> orderers = new LinkedList<>();

        for (String orderName : sampleOrg.getOrdererNames()) {

            Properties ordererProperties = testConfig.getOrdererProperties(orderName);

            //example of setting keepAlive to avoid timeouts on inactive http2 connections.
            // Under 5 minutes would require changes to server side to accept faster ping rates.
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveWithoutCalls", new Object[] {true});

            orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName),
                    ordererProperties));
        }

        //Just pick the first orderer in the list to create the channel.

        Orderer anOrderer = orderers.iterator().next();
//        orderers.remove(anOrderer);

        ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(TEST_FIXTURES_PATH + "/sdkintegration/e2e-2Orgs/" + TestConfig.FAB_CONFIG_GEN_VERS + "/" + name + ".tx"));

        //Create channel that has only one signer that is this orgs peer admin. If channel creation policy needed more signature they would need to be added too.
        Channel newChannel = client.newChannel(name, anOrderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin()));

        out("Created channel %s", name);

        boolean everyother = true; //test with both cases when doing peer eventing.
        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName); //test properties for peer.. if any.
            if (peerProperties == null) {
                peerProperties = new Properties();
            }

            //Example of setting specific options on grpc's NettyChannelBuilder
            peerProperties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);

            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            if (doPeerEventing && everyother) {
                newChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY, PeerRole.EVENT_SOURCE))); //Default is all roles.
            } else {
                // Set peer to not be all roles but eventing.
                newChannel.joinPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY)));
            }
            out("Peer %s joined channel %s", peerName, name);
            everyother = !everyother;
        }
        //just for testing ...
        if (doPeerEventing) {
            // Make sure there is one of each type peer at the very least.
            assertFalse(newChannel.getPeers(EnumSet.of(PeerRole.EVENT_SOURCE)).isEmpty());
            assertFalse(newChannel.getPeers(PeerRole.NO_EVENT_SOURCE).isEmpty());
        }

        for (Orderer orderer : orderers) { //add remaining orderers if any.
            newChannel.addOrderer(orderer);
        }

        for (String eventHubName : sampleOrg.getEventHubNames()) {

            final Properties eventHubProperties = testConfig.getEventHubProperties(eventHubName);

            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});

            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    eventHubProperties);
            newChannel.addEventHub(eventHub);
        }

        newChannel.initialize();

        out("Finished initialization channel %s", name);

        //Just checks if channel can be serialized and deserialized .. otherwise this is just a waste :)
        byte[] serializedChannelBytes = newChannel.serializeChannel();
        newChannel.shutdown(true);

        return client.deSerializeChannel(serializedChannelBytes).initialize();

    }



    Channel reConstructChannel(String name, HFClient client, SampleOrg sampleOrg) throws Exception {
        ////////////////////////////
        //Construct the channel
        //

        out("Constructing channel %s", name);

        //boolean doPeerEventing = false;
        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && BAR_CHANNEL_NAME.equals(name);
//        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && FOO_CHANNEL_NAME.equals(name);
        //Only peer Admin org
        client.setUserContext(sampleOrg.getPeerAdmin());

        Collection<Orderer> orderers = new LinkedList<>();

        for (String orderName : sampleOrg.getOrdererNames()) {

            Properties ordererProperties = testConfig.getOrdererProperties(orderName);

            //example of setting keepAlive to avoid timeouts on inactive http2 connections.
            // Under 5 minutes would require changes to server side to accept faster ping rates.
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveWithoutCalls", new Object[] {true});

            orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName),
                    ordererProperties));
        }

        //Just pick the first orderer in the list to create the channel.

        Orderer anOrderer = orderers.iterator().next();
//        orderers.remove(anOrderer);

        ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(TEST_FIXTURES_PATH + "/sdkintegration/e2e-2Orgs/" + TestConfig.FAB_CONFIG_GEN_VERS + "/" + name + ".tx"));

        //Create channel that has only one signer that is this orgs peer admin. If channel creation policy needed more signature they would need to be added too.
//        Channel newChannel = client.newChannel(name, anOrderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin()));
           Channel newChannel=client.newChannel(name);
        out("Created channel %s", name);

        boolean everyother = true; //test with both cases when doing peer eventing.
        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName); //test properties for peer.. if any.
            if (peerProperties == null) {
                peerProperties = new Properties();
            }

            //Example of setting specific options on grpc's NettyChannelBuilder
            peerProperties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);

            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            if (doPeerEventing && everyother) {
                newChannel.addPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY, PeerRole.EVENT_SOURCE))); //Default is all roles.
            } else {
                // Set peer to not be all roles but eventing.
                newChannel.addPeer(peer, createPeerOptions().setPeerRoles(EnumSet.of(PeerRole.ENDORSING_PEER, PeerRole.LEDGER_QUERY, PeerRole.CHAINCODE_QUERY)));
            }
            out("Peer %s joined channel %s", peerName, name);
            everyother = !everyother;
        }
        //just for testing ...
        if (doPeerEventing) {
            // Make sure there is one of each type peer at the very least.
            assertFalse(newChannel.getPeers(EnumSet.of(PeerRole.EVENT_SOURCE)).isEmpty());
            assertFalse(newChannel.getPeers(PeerRole.NO_EVENT_SOURCE).isEmpty());
        }

        for (Orderer orderer : orderers) { //add remaining orderers if any.
            newChannel.addOrderer(orderer);
        }

        for (String eventHubName : sampleOrg.getEventHubNames()) {

            final Properties eventHubProperties = testConfig.getEventHubProperties(eventHubName);

            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});

            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    eventHubProperties);
            newChannel.addEventHub(eventHub);
        }

        newChannel.initialize();

        out("Finished initialization channel %s", name);

        //Just checks if channel can be serialized and deserialized .. otherwise this is just a waste :)
        byte[] serializedChannelBytes = newChannel.serializeChannel();
        newChannel.shutdown(true);

        return client.deSerializeChannel(serializedChannelBytes).initialize();

    }


    private void waitOnFabric(int additional) {
        //NOOP today

    }


}
